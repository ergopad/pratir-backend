package models

import java.util.UUID

final case class HighlightSaleRequest (
    saleId: UUID,
    verificationToken: String
)
