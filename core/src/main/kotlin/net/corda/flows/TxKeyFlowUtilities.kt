package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Party
import net.corda.core.crypto.composite
import net.corda.core.flows.FlowLogic
import net.corda.core.node.ServiceHub
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.unwrap
import java.security.cert.Certificate

object TxKeyFlowUtilities {
    /**
     * Receive a key from a counterparty.
     */
    @Suspendable
    fun receiveKey(flow: FlowLogic<*>, otherSide: Party): Pair<CompositeKey, Certificate?> {
        val untrustedKey = flow.receive<Response>(otherSide)
        return untrustedKey.unwrap {
            // TODO: Verify the certificate connects the given key to the counterparty, once we have certificates
            Pair(it.key, it.certificate)
        }
    }

    /**
     * Generates a new key and then returns it to the counterparty and as the result from the function.
     */
    @Suspendable
    fun provideKey(flow: FlowLogic<*>, otherSide: Party): CompositeKey {
        val key = flow.serviceHub.keyManagementService.freshKey().public.composite
        // TODO: Generate and sign certificate for the key, once we have signing support for composite keys
        //       (in this case the legal identity key)
        flow.send(otherSide, Response(key, null))
        return key
    }

    @CordaSerializable
    data class Response(val key: CompositeKey, val certificate: Certificate?)
}