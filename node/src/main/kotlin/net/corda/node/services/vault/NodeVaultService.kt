package net.corda.node.services.vault

import co.paralleluniverse.strands.Strand
import io.requery.TransactionIsolation
import io.requery.kotlin.`in`
import io.requery.kotlin.eq
import io.requery.kotlin.isNull
import io.requery.kotlin.notNull
import net.corda.contracts.asset.Cash
import net.corda.core.ThreadBox
import net.corda.core.bufferUntilSubscribed
import net.corda.core.contracts.*
import net.corda.core.crypto.AbstractParty
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Party
import net.corda.core.crypto.SecureHash
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.NoStatesAvailableException
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.unconsumedStates
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.serialization.storageKryo
import net.corda.core.tee
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.trace
import net.corda.node.services.database.RequeryConfiguration
import net.corda.node.services.statemachine.FlowStateMachineImpl
import net.corda.node.services.vault.schemas.*
import net.corda.node.utilities.bufferUntilDatabaseCommit
import net.corda.node.utilities.wrapWithDatabaseTransaction
import rx.Observable
import rx.subjects.PublishSubject
import java.lang.Thread.sleep
import java.security.PublicKey
import java.sql.SQLException
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Currently, the node vault service is a very simple RDBMS backed implementation.  It will change significantly when
 * we add further functionality as the design for the vault and vault service matures.
 *
 * This class needs database transactions to be in-flight during method calls and init, and will throw exceptions if
 * this is not the case.
 *
 * TODO: move query / filter criteria into the database query.
 * TODO: keep an audit trail with time stamps of previously unconsumed states "as of" a particular point in time.
 * TODO: have transaction storage do some caching.
 */
class NodeVaultService(private val services: ServiceHub, dataSourceProperties: Properties) : SingletonSerializeAsToken(), VaultService {

    private companion object {
        val log = loggerFor<NodeVaultService>()
    }

    val configuration = RequeryConfiguration(dataSourceProperties)
    val session = configuration.sessionForModel(Models.VAULT)

    private val mutex = ThreadBox(content = object {

        val _updatesPublisher = PublishSubject.create<Vault.Update>()
        val _rawUpdatesPublisher = PublishSubject.create<Vault.Update>()
        val _updatesInDbTx = _updatesPublisher.wrapWithDatabaseTransaction().asObservable()

        // For use during publishing only.
        val updatesPublisher: rx.Observer<Vault.Update> get() = _updatesPublisher.bufferUntilDatabaseCommit().tee(_rawUpdatesPublisher)
    })

    private fun recordUpdate(update: Vault.Update): Vault.Update {
        if (update != Vault.NoUpdate) {
            val producedStateRefs = update.produced.map { it.ref }
            val producedStateRefsMap = update.produced.associateBy { it.ref }
            val consumedStateRefs = update.consumed.map { it.ref }
            log.trace { "Removing $consumedStateRefs consumed contract states and adding $producedStateRefs produced contract states to the database." }

            session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                producedStateRefsMap.forEach { it ->
                    val state = VaultStatesEntity().apply {
                        txId = it.key.txhash.toString()
                        index = it.key.index
                        stateStatus = Vault.StateStatus.UNCONSUMED
                        contractStateClassName = it.value.state.data.javaClass.name
                        contractState = it.value.state.serialize(storageKryo()).bytes
                        notaryName = it.value.state.notary.name
                        notaryKey = it.value.state.notary.owningKey.toBase58String()
                        recordedTime = services.clock.instant()
                    }
                    insert(state)
                }
                // TODO: awaiting support of UPDATE WHERE <Composite key> IN in Requery DSL
                consumedStateRefs.forEach { stateRef ->
                    val queryKey = io.requery.proxy.CompositeKey(mapOf(VaultStatesEntity.TX_ID to stateRef.txhash.toString(),
                            VaultStatesEntity.INDEX to stateRef.index))
                    val state = findByKey(VaultStatesEntity::class, queryKey)
                    state?.run {
                        stateStatus = Vault.StateStatus.CONSUMED
                        consumedTime = services.clock.instant()
                        // remove lock (if held)
                        if (lockId != null) {
                            lockId = null
                            lockUpdateTime = services.clock.instant()
                            log.trace("Releasing soft lock on consumed state: $stateRef")
                        }
                        update(state)
                    }
                }
            }
        }
        return update
    }

    // TODO: consider moving this logic outside the vault
    // TODO: revisit the concurrency safety of this logic when we move beyond single threaded SMM.
    //       For example, we update currency totals in a non-deterministic order and so expose ourselves to deadlock.
    private fun maybeUpdateCashBalances(update: Vault.Update) {
        if (update.containsType<Cash.State>()) {
            val consumed = sumCashStates(update.consumed)
            val produced = sumCashStates(update.produced)
            (produced.keys + consumed.keys).map { currency ->
                val producedAmount = produced[currency] ?: Amount(0, currency)
                val consumedAmount = consumed[currency] ?: Amount(0, currency)

                val cashBalanceEntity = VaultCashBalancesEntity()
                cashBalanceEntity.currency = currency.currencyCode
                cashBalanceEntity.amount = producedAmount.quantity - consumedAmount.quantity

                session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                    val state = findByKey(VaultCashBalancesEntity::class, currency.currencyCode)
                    state?.run {
                        amount += producedAmount.quantity - consumedAmount.quantity
                    }
                    upsert(state ?: cashBalanceEntity)
                    log.trace("Updating Cash balance for $currency by ${cashBalanceEntity.amount}")
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sumCashStates(states: Iterable<StateAndRef<ContractState>>): Map<Currency, Amount<Currency>> {
        return states.mapNotNull { (it.state.data as? FungibleAsset<Currency>)?.amount }
                .groupBy { it.token.product }
                .mapValues { it.value.map { Amount(it.quantity, it.token.product) }.sumOrThrow() }
    }

    override val cashBalances: Map<Currency, Amount<Currency>> get() {
        val cashBalancesByCurrency =
                session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                    val balances = select(VaultSchema.VaultCashBalances::class)
                    balances.get().toList()
                }
        return cashBalancesByCurrency.associateBy({ Currency.getInstance(it.currency) },
                { Amount(it.amount, Currency.getInstance(it.currency)) })
    }

    override val rawUpdates: Observable<Vault.Update>
        get() = mutex.locked { _rawUpdatesPublisher }

    override val updates: Observable<Vault.Update>
        get() = mutex.locked { _updatesInDbTx }

    override fun track(): Pair<Vault<ContractState>, Observable<Vault.Update>> {
        return mutex.locked {
            Pair(Vault(unconsumedStates<ContractState>()), _updatesPublisher.bufferUntilSubscribed().wrapWithDatabaseTransaction())
        }
    }

    override fun <T: ContractState> states(clazzes: Set<Class<T>>, statuses: EnumSet<Vault.StateStatus>, includeSoftLockedStates: Boolean): Iterable<StateAndRef<T>> {
        val stateAndRefs =
            session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                var query = select(VaultSchema.VaultStates::class)
                                .where(VaultSchema.VaultStates::stateStatus `in` statuses)
                // TODO: temporary fix to continue supporting track() function (until becomes Typed)
                if (!clazzes.map {it.name}.contains(ContractState::class.java.name))
                    query.and (VaultSchema.VaultStates::contractStateClassName `in` (clazzes.map { it.name }))
                if (!includeSoftLockedStates)
                    query.and(VaultSchema.VaultStates::lockId.isNull())
                val iterator = query.get().iterator()
                Sequence{iterator}
                        .map { it ->
                            val stateRef = StateRef(SecureHash.parse(it.txId), it.index)
                            val state = it.contractState.deserialize<TransactionState<T>>(storageKryo())
                            StateAndRef(state, stateRef)
                        }
            }
        return stateAndRefs.asIterable()
    }

    override fun statesForRefs(refs: List<StateRef>): Map<StateRef, TransactionState<*>?> {
        val stateAndRefs =
                session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                    var results: List<StateAndRef<*>> = emptyList()
                    refs.forEach {
                        val result = select(VaultSchema.VaultStates::class)
                                .where(VaultSchema.VaultStates::stateStatus eq Vault.StateStatus.UNCONSUMED)
                                .and(VaultSchema.VaultStates::txId eq it.txhash.toString())
                                .and(VaultSchema.VaultStates::index eq it.index)
                        result.get()?.each {
                            val stateRef = StateRef(SecureHash.parse(it.txId), it.index)
                            val state = it.contractState.deserialize<TransactionState<*>>(storageKryo())
                            results += StateAndRef(state, stateRef)
                        }
                    }
                    results
                }

        return stateAndRefs.associateBy({ it.ref }, { it.state })
    }

    override fun notifyAll(txns: Iterable<WireTransaction>) {
        val ourKeys = services.keyManagementService.keys.keys
        val netDelta = txns.fold(Vault.NoUpdate) { netDelta, txn -> netDelta + makeUpdate(txn, ourKeys) }
        if (netDelta != Vault.NoUpdate) {
            recordUpdate(netDelta)
            maybeUpdateCashBalances(netDelta)
            mutex.locked {
                // flowId required by SoftLockManager to perform auto-registration of soft locks for new states
                val uuid = (Strand.currentStrand() as? FlowStateMachineImpl<*>)?.id?.uuid
                val vaultUpdate = if (uuid != null) netDelta.copy(flowId = uuid) else netDelta
                updatesPublisher.onNext(vaultUpdate)
            }
        }
    }

    override fun addNoteToTransaction(txnId: SecureHash, noteText: String) {
        session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
            val txnNoteEntity = VaultTxnNoteEntity()
            txnNoteEntity.txId = txnId.toString()
            txnNoteEntity.note = noteText
            insert(txnNoteEntity)
        }
    }

    override fun getTransactionNotes(txnId: SecureHash): Iterable<String> {
        return session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
            (select(VaultSchema.VaultTxnNote::class) where (VaultSchema.VaultTxnNote::txId eq txnId.toString())).get().asIterable().map { it.note }
        }
    }

    @Throws(NoStatesAvailableException::class)
    override fun softLockReserve(id: UUID, stateRefs: Set<StateRef>) {
        if (stateRefs.isNotEmpty()) {
            val stateRefsAsStr = stateRefsToCompositeKeyStr(stateRefs.toList())
            val softLockTimestamp = services.clock.instant()
            // TODO: awaiting support of UPDATE WHERE <Composite key> IN in Requery DSL
            val updateStatement = """
                UPDATE VAULT_STATES SET lock_id = '$id', lock_timestamp = '$softLockTimestamp'
                WHERE ((transaction_id, output_index) IN ($stateRefsAsStr))
                AND (state_status = 0)
                AND ((lock_id is null) OR (lock_id = '$id'));
            """
            val statement = configuration.jdbcSession().createStatement()
            log.debug(updateStatement)
            try {
                val rs = statement.executeUpdate(updateStatement)
                if (rs > 0 && rs == stateRefs.size) {
                    log.trace("Reserving soft lock states for $id: $stateRefs")
                }
                else {
                    // revert partial soft locks
                    val revertUpdateStatement = """
                        UPDATE VAULT_STATES SET lock_id = null
                        WHERE ((transaction_id, output_index) IN ($stateRefsAsStr))
                        AND (lock_timestamp = '$softLockTimestamp') AND (lock_id = '$id');
                    """
                    log.debug(revertUpdateStatement)
                    val rsr = statement.executeUpdate(revertUpdateStatement)
                    if (rsr > 0) {
                        log.trace("Reverting $rsr partially soft locked states for $id")
                    }
                    throw NoStatesAvailableException("Attempted to reserve $stateRefs for $id but only $rs rows available")
                }
            }
            catch (e: SQLException) {
                log.error("""soft lock update error attempting to reserve states: $stateRefs for $id
                            $e.
                        """)
                throw NoStatesAvailableException("Failed to reserve $stateRefs for $id", e)
            }
            finally { statement.close() }
        }
    }

    override fun softLockRelease(id: UUID, stateRefs: Set<StateRef>?) {
        if (stateRefs == null) {
            session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                val update = update(VaultStatesEntity::class)
                        .set(VaultStatesEntity.LOCK_ID, null)
                        .set(VaultStatesEntity.LOCK_UPDATE_TIME, services.clock.instant())
                        .where (VaultStatesEntity.STATE_STATUS eq Vault.StateStatus.UNCONSUMED)
                        .and (VaultStatesEntity.LOCK_ID eq id.toString()).get()
                if (update.value() > 0) {
                    log.trace("Releasing ${update.value()} soft locked states for $id")
                }
            }
        }
        else if (stateRefs.isNotEmpty()) {
            val stateRefsAsStr = stateRefsToCompositeKeyStr(stateRefs.toList())
            // TODO: awaiting support of UPDATE WHERE <Composite key> IN in Requery DSL
            val updateStatement = """
                UPDATE VAULT_STATES SET lock_id = null, lock_timestamp = '${services.clock.instant()}'
                WHERE (transaction_id, output_index) IN ($stateRefsAsStr)
                AND (state_status = 0) AND (lock_id = '$id');
            """
            val statement = configuration.jdbcSession().createStatement()
            log.debug(updateStatement)
            try {
                val rs = statement.executeUpdate(updateStatement)
                if (rs > 0) {
                    log.trace("Releasing $rs soft locked states for $id and stateRefs $stateRefs")
                }
            } catch (e: SQLException) {
                log.error("""soft lock update error attempting to release states for $id and $stateRefs")
                $e.
            """)
            } finally {
                statement.close()
            }
        }
    }

    // coin selection retry loop counter, sleep (msecs) and lock for selecting states
    val MAX_RETRIES = 5
    val RETRY_SLEEP = 100
    val spendLock: ReentrantLock = ReentrantLock()

    internal fun <T : ContractState> unconsumedStatesForSpending(amount: Amount<Currency>, onlyFromIssuerParties: Set<AbstractParty>? = null, notary: Party? = null, lockId: UUID): List<StateAndRef<T>> {

        val issuerKeysStr = onlyFromIssuerParties?.fold("") { left, right -> left + "('${right.owningKey.toBase58String()}')," }?.dropLast(1)
        var stateAndRefs = mutableListOf<StateAndRef<T>>()

        // TODO: Need to provide a database provider independent means of performing this function.
        //       We are using an H2 specific means of selecting a minimum set of rows that match a request amount of coins:
        //       1) There is no standard SQL mechanism of calculating a cumulative total on a field and restricting row selection on the
        //          running total of such an accumulator
        //       2) H2 uses session variables to perform this accumulator function:
        //          http://www.h2database.com/html/functions.html#set
        //       3) H2 does not support JOIN's in FOR UPDATE (hence we are forced to execute 2 queries)

        for (retryCount in 1..MAX_RETRIES) {

            spendLock.withLock {
                val statement = configuration.jdbcSession().createStatement()
                try {
                    statement.execute("CALL SET(@t, 0);")

                    // we select spendable states irrespective of lock but prioritised by unlocked ones (Eg. null)
                    // the softLockReserve update will detect whether we try to lock states locked by others
                    val selectJoin = """
                        SELECT vs.transaction_id, vs.output_index, vs.contract_state, SET(@t, ifnull(@t,0)+ccs.pennies) total_pennies
                        FROM vault_states AS vs, contract_cash_states AS ccs
                        WHERE vs.transaction_id = ccs.transaction_id AND vs.output_index = ccs.output_index
                        AND vs.state_status = 0
                        AND ccs.ccy_code = '${amount.token}' and @t <= ${amount.quantity}
                        """ +
                            (if (notary != null)
                                " AND vs.notary_key = '${notary.owningKey.toBase58String()}'" else "") +
                            (if (issuerKeysStr != null)
                                " AND ccs.issuer_key IN $issuerKeysStr" else "") +
                            " ORDER BY vs.lock_id NULLS FIRST"

                    // Retrieve spendable state refs
                    val rs = statement.executeQuery(selectJoin)
                    stateAndRefs.clear()
                    log.debug(selectJoin)
                    var pennies = 0L
                    while (rs.next()) {
                        val txHash = SecureHash.parse(rs.getString(1))
                        val index = rs.getInt(2)
                        val stateRef = StateRef(txHash, index)
                        val state = rs.getBytes(3).deserialize<TransactionState<T>>(storageKryo())
                        pennies = rs.getLong(4)
                        stateAndRefs.add(StateAndRef(state, stateRef))
                    }

                    if (stateAndRefs.isNotEmpty() && pennies >= amount.quantity) {
                        // we should a minimum number of states to satisfy our selection `amount` criteria
                        log.trace("Coin selection for $amount retrieved ${stateAndRefs.count()} states totalling $pennies pennies: $stateAndRefs")

                        // update database
                        softLockReserve(lockId, stateAndRefs.map { it.ref }.toSet())
                        return stateAndRefs
                    }
                    log.trace("Coin selection requested $amount but retrieved $pennies pennies")
                    return stateAndRefs

                } catch (e: SQLException) {
                    log.error("""Failed retrieving unconsumed states for: amount [$amount], onlyFromIssuerParties [$onlyFromIssuerParties], notary [$notary], lockId [$lockId]
                            $e.
                        """)
                } catch (e: NoStatesAvailableException) {
                    log.warn(e.message)
                    // retry only if there are locked states that may become available again (or consumed with change)
                } finally {
                    statement.close()
                }
            }

            log.warn("Coin selection failed on attempt $retryCount")
            // TODO: revisit the back off strategy for contended spending.
            if (retryCount != MAX_RETRIES) {
                sleep(RETRY_SLEEP * retryCount.toLong())
            }
        }

        log.warn("Insufficient spendable states identified for $amount")
        return stateAndRefs
    }

    override fun <T : ContractState> softLockedStates(lockId: UUID?): List<StateAndRef<T>> {
        val stateAndRefs =
            session.withTransaction(TransactionIsolation.REPEATABLE_READ) {
                var query = select(VaultSchema.VaultStates::class)
                        .where(VaultSchema.VaultStates::stateStatus eq Vault.StateStatus.UNCONSUMED)
                        .and(VaultSchema.VaultStates::contractStateClassName eq Cash.State::class.java.name)
                if (lockId != null)
                    query.and(VaultSchema.VaultStates::lockId eq lockId)
                else
                    query.and(VaultSchema.VaultStates::lockId.notNull())
                query.get()
                        .map { it ->
                            val stateRef = StateRef(SecureHash.parse(it.txId), it.index)
                            val state = it.contractState.deserialize<TransactionState<T>>(storageKryo())
                            StateAndRef(state, stateRef)
                        }.toList()
            }
        return stateAndRefs
    }

    /**
     * Generate a transaction that moves an amount of currency to the given pubkey.
     *
     * @param onlyFromParties if non-null, the asset states will be filtered to only include those issued by the set
     *                        of given parties. This can be useful if the party you're trying to pay has expectations
     *                        about which type of asset claims they are willing to accept.
     */
    override fun generateSpend(tx: TransactionBuilder,
                               amount: Amount<Currency>,
                               to: CompositeKey,
                               onlyFromParties: Set<AbstractParty>?): Pair<TransactionBuilder, List<CompositeKey>> {
        // Discussion
        //
        // This code is analogous to the Wallet.send() set of methods in bitcoinj, and has the same general outline.
        //
        // First we must select a set of asset states (which for convenience we will call 'coins' here, as in bitcoinj).
        // The input states can be considered our "vault", and may consist of different products, and with different
        // issuers and deposits.
        //
        // Coin selection is a complex problem all by itself and many different approaches can be used. It is easily
        // possible for different actors to use different algorithms and approaches that, for example, compete on
        // privacy vs efficiency (number of states created). Some spends may be artificial just for the purposes of
        // obfuscation and so on.
        //
        // Having selected input states of the correct asset, we must craft output states for the amount we're sending and
        // the "change", which goes back to us. The change is required to make the amounts balance. We may need more
        // than one change output in order to avoid merging assets from different deposits. The point of this design
        // is to ensure that ledger entries are immutable and globally identifiable.
        //
        // Finally, we add the states to the provided partial transaction.

        // Retrieve unspent and unlocked cash states that meet our spending criteria.
        val acceptableCoins = unconsumedStatesForSpending<Cash.State>(amount, onlyFromParties, tx.notary, tx.lockId)

        // TODO: We should be prepared to produce multiple transactions spending inputs from
        // different notaries, or at least group states by notary and take the set with the
        // highest total value.

        // notary may be associated with locked state only
        tx.notary = acceptableCoins.firstOrNull()?.state?.notary

        val (gathered, gatheredAmount) = gatherCoins(acceptableCoins, amount)

        val takeChangeFrom = gathered.firstOrNull()
        val change = if (takeChangeFrom != null && gatheredAmount > amount) {
            Amount(gatheredAmount.quantity - amount.quantity, takeChangeFrom.state.data.amount.token)
        } else {
            null
        }
        val keysUsed = gathered.map { it.state.data.owner }

        val states = gathered.groupBy { it.state.data.amount.token.issuer }.map {
            val coins = it.value
            val totalAmount = coins.map { it.state.data.amount }.sumOrThrow()
            deriveState(coins.first().state, totalAmount, to)
        }.sortedBy { it.data.amount.quantity }

        val outputs = if (change != null) {
            // Just copy a key across as the change key. In real life of course, this works but leaks private data.
            // In bitcoinj we derive a fresh key here and then shuffle the outputs to ensure it's hard to follow
            // value flows through the transaction graph.
            val existingOwner = gathered.first().state.data.owner
            // Add a change output and adjust the last output downwards.
            states.subList(0, states.lastIndex) +
                    states.last().let {
                        val spent = it.data.amount.withoutIssuer() - change.withoutIssuer()
                        deriveState(it, Amount(spent.quantity, it.data.amount.token), it.data.owner)
                    } +
                    states.last().let {
                        deriveState(it, Amount(change.quantity, it.data.amount.token), existingOwner)
                    }
        } else states

        for (state in gathered) tx.addInputState(state)
        for (state in outputs) tx.addOutputState(state)

        // What if we already have a move command with the right keys? Filter it out here or in platform code?
        tx.addCommand(Cash().generateMoveCommand(), keysUsed)

        // update Vault
        //        notify(tx.toWireTransaction())
        // Vault update must be completed AFTER transaction is recorded to ledger storage!!!
        // (this is accomplished within the recordTransaction function)

        return Pair(tx, keysUsed)
    }

    private fun deriveState(txState: TransactionState<Cash.State>, amount: Amount<Issued<Currency>>, owner: CompositeKey)
            = txState.copy(data = txState.data.copy(amount = amount, owner = owner))

    /**
     * Gather assets from the given list of states, sufficient to match or exceed the given amount.
     *
     * @param acceptableCoins list of states to use as inputs.
     * @param amount the amount to gather states up to.
     * @throws InsufficientBalanceException if there isn't enough value in the states to cover the requested amount.
     */
    @Throws(InsufficientBalanceException::class)
    private fun gatherCoins(acceptableCoins: Collection<StateAndRef<Cash.State>>,
                            amount: Amount<Currency>): Pair<ArrayList<StateAndRef<Cash.State>>, Amount<Currency>> {
        val gathered = arrayListOf<StateAndRef<Cash.State>>()
        var gatheredAmount = Amount(0, amount.token)
        for (c in acceptableCoins) {
            if (gatheredAmount >= amount) break
            gathered.add(c)
            gatheredAmount += Amount(c.state.data.amount.quantity, amount.token)
        }

        if (gatheredAmount < amount) {
            log.trace("Insufficient balance: requested $amount, available $gatheredAmount}")
            throw InsufficientBalanceException(amount - gatheredAmount)
        }

        log.trace("Gathered coins: requested $amount, available $gatheredAmount, change: ${gatheredAmount - amount}")

        return Pair(gathered, gatheredAmount)
    }

    private fun makeUpdate(tx: WireTransaction, ourKeys: Set<PublicKey>): Vault.Update {
        val ourNewStates = tx.outputs.
                filter { isRelevant(it.data, ourKeys) }.
                map { tx.outRef<ContractState>(it.data) }

        // Retrieve all unconsumed states for this transaction's inputs
        val consumedStates = HashSet<StateAndRef<ContractState>>()
        if (tx.inputs.isNotEmpty()) {
            val stateRefs = stateRefsToCompositeKeyStr(tx.inputs)
            // TODO: using native JDBC until requery supports SELECT WHERE COMPOSITE_KEY IN
            // https://github.com/requery/requery/issues/434
            val statement = configuration.jdbcSession().createStatement()
            try {
                // TODO: upgrade to Requery 1.2.0 and rewrite with Requery DSL (https://github.com/requery/requery/issues/434)
                val rs = statement.executeQuery("SELECT transaction_id, output_index, contract_state " +
                        "FROM vault_states " +
                        "WHERE ((transaction_id, output_index) IN ($stateRefs)) " +
                        "AND (state_status = 0)")
                while (rs.next()) {
                    val txHash = SecureHash.parse(rs.getString(1))
                    val index = rs.getInt(2)
                    val state = rs.getBytes(3).deserialize<TransactionState<ContractState>>(storageKryo())
                    consumedStates.add(StateAndRef(state, StateRef(txHash, index)))
                }
            } catch (e: SQLException) {
                log.error("""Failed retrieving state refs for: $stateRefs
                            $e.
                        """)
            }
            finally { statement.close() }
        }

        // Is transaction irrelevant?
        if (consumedStates.isEmpty() && ourNewStates.isEmpty()) {
            log.trace { "tx ${tx.id} was irrelevant to this vault, ignoring" }
            return Vault.NoUpdate
        }

        return Vault.Update(consumedStates, ourNewStates.toHashSet())
    }

    // TODO : Persists this in DB.
    private val authorisedUpgrade = mutableMapOf<StateRef, Class<out UpgradedContract<*, *>>>()

    override fun getAuthorisedContractUpgrade(ref: StateRef) = authorisedUpgrade[ref]

    override fun authoriseContractUpgrade(stateAndRef: StateAndRef<*>, upgradedContractClass: Class<out UpgradedContract<*, *>>) {
        val upgrade = upgradedContractClass.newInstance()
        if (upgrade.legacyContract != stateAndRef.state.data.contract.javaClass) {
            throw IllegalArgumentException("The contract state cannot be upgraded using provided UpgradedContract.")
        }
        authorisedUpgrade.put(stateAndRef.ref, upgradedContractClass)
    }

    override fun deauthoriseContractUpgrade(stateAndRef: StateAndRef<*>) {
        authorisedUpgrade.remove(stateAndRef.ref)
    }

    private fun isRelevant(state: ContractState, ourKeys: Set<PublicKey>) = when (state) {
        is OwnableState -> state.owner.containsAny(ourKeys)
    // It's potentially of interest to the vault
        is LinearState -> state.isRelevant(ourKeys)
        else -> false
    }

    /**
     * Helper method to generate a string formatted list of Composite Keys for SQL IN clause
     */
    private fun stateRefsToCompositeKeyStr(stateRefs: List<StateRef>): String {
        return stateRefs.fold("") { stateRefsAsStr, it -> stateRefsAsStr + "('${it.txhash}','${it.index}')," }.dropLast(1)
    }
}