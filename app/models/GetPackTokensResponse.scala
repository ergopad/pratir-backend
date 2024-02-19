package models

import play.api.libs.json.Json
import java.util.UUID

final case class GetPackTokensResponse(
    packTokens: Seq[String]
)

object GetPackTokensResponse {
  implicit val json = Json.format[GetPackTokensResponse]
}
