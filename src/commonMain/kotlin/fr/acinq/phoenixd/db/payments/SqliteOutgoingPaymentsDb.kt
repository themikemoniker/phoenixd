package fr.acinq.phoenixd.db.payments

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.db.OnChainOutgoingPayment
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.db.OutgoingPaymentsDb
import fr.acinq.lightning.utils.UUID
import fr.acinq.phoenixd.db.sqldelight.PhoenixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteOutgoingPaymentsDb(private val database: PhoenixDatabase) : OutgoingPaymentsDb {

    override suspend fun addLightningOutgoingPaymentParts(parentId: UUID, parts: List<LightningOutgoingPayment.Part>) {
        withContext(Dispatchers.Default) {
            database.transaction {
                val payment = database.paymentsOutgoingQueries.get(parentId).executeAsOneOrNull() as LightningOutgoingPayment
                val payment1 = payment.copy(parts = payment.parts + parts)
                database.paymentsOutgoingQueries.update(
                    id = parentId,
                    data = payment1,
                    completed_at = null,
                    succeeded_at = null
                )
            }
            parts.forEach { part ->
                database.paymentsOutgoingQueries.insertPartLink(part_id = part.id, parent_id = parentId)
            }
        }
    }

    override suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment) {
        withContext(Dispatchers.Default) {
            database.transaction {
                when (outgoingPayment) {
                    is LightningOutgoingPayment -> {
                        database.paymentsOutgoingQueries.insert(
                            id = outgoingPayment.id,
                            payment_hash = outgoingPayment.paymentHash,
                            tx_id = null,
                            created_at = outgoingPayment.createdAt,
                            completed_at = outgoingPayment.completedAt,
                            succeeded_at = outgoingPayment.succeededAt,
                            data_ = outgoingPayment
                        )
                        outgoingPayment.parts.forEach { part ->
                            database.paymentsOutgoingQueries.insertPartLink(part_id = part.id, parent_id = outgoingPayment.id)
                        }
                    }
                    is OnChainOutgoingPayment -> {
                        database.paymentsOutgoingQueries.insert(
                            id = outgoingPayment.id,
                            payment_hash = null,
                            tx_id = outgoingPayment.txId,
                            created_at = outgoingPayment.createdAt,
                            completed_at = outgoingPayment.completedAt,
                            succeeded_at = outgoingPayment.succeededAt,
                            data_ = outgoingPayment
                        )
                        database.onChainTransactionsQueries.insert(
                            payment_id = outgoingPayment.id,
                            tx_id = outgoingPayment.txId,
                            confirmed_at = outgoingPayment.confirmedAt,
                            locked_at = outgoingPayment.lockedAt
                        )
                    }
                }
            }
        }
    }

    override suspend fun completeLightningOutgoingPayment(id: UUID, status: LightningOutgoingPayment.Status.Completed) {
        withContext(Dispatchers.Default) {
            database.transaction {
                val payment = database.paymentsOutgoingQueries.get(id).executeAsOneOrNull() as LightningOutgoingPayment
                val payment1 = payment.copy(status = status)
                database.paymentsOutgoingQueries.update(
                    id = id,
                    data = payment1,
                    completed_at = payment1.completedAt,
                    succeeded_at = payment1.succeededAt,
                )
            }
        }
    }

    override suspend fun completeLightningOutgoingPaymentPart(parentId: UUID, partId: UUID, status: LightningOutgoingPayment.Part.Status.Completed) {
        withContext(Dispatchers.Default) {
            database.transaction {
                val payment = database.paymentsOutgoingQueries.get(parentId).executeAsOneOrNull() as LightningOutgoingPayment
                val payment1 = payment.copy(parts = payment.parts.map {
                    when {
                        it.id == partId -> it.copy(status = status)
                        else -> it
                    }
                })
                database.paymentsOutgoingQueries.update(
                    id = parentId,
                    data = payment1,
                    completed_at = null, // parts do not update parent timestamps
                    succeeded_at = null
                )
            }
        }
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? {
        return withContext(Dispatchers.Default) {
            database.paymentsOutgoingQueries.get(id).executeAsOneOrNull() as? LightningOutgoingPayment
        }
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? {
        return withContext(Dispatchers.Default) {
            database.transactionWithResult {
                database.paymentsOutgoingQueries.getParentId(partId).executeAsOneOrNull()?.let { paymentId ->
                    database.paymentsOutgoingQueries.get(paymentId).executeAsOneOrNull() as? LightningOutgoingPayment
                }
            }
        }
    }

    override suspend fun listLightningOutgoingPayments(paymentHash: ByteVector32): List<LightningOutgoingPayment> {
        return withContext(Dispatchers.Default) {
            database.paymentsOutgoingQueries.listByPaymentHash(paymentHash).executeAsList().filterIsInstance<LightningOutgoingPayment>()
        }
    }
}