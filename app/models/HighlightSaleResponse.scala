package models

import java.util.UUID

final case class HighlightSaleResponse (
    status: String,
    message: String,
    id: Option[UUID]
)
