//! Card-key billing integration (port of `previewRedeemCard` / `redeemCard`).

use crate::api::CloudResult;
use crate::models::{CloudCardPreview, CloudRedeemResponse};
use crate::sync::SyncEngine;

impl<'a> SyncEngine<'a> {
    /// Preview card metadata before committing redemption.
    pub async fn preview_redeem_card(&self, code: &str) -> CloudResult<CloudCardPreview> {
        let _ = self.signed_in()?;
        let endpoint = self.endpoint("api/console/cards/preview");
        let resp = self.client.preview_redeem_card(&endpoint, code.trim()).await?;
        Ok(resp.card)
    }

    /// Commit card redemption, refreshing user quota entitlements on success.
    pub async fn redeem_card(&self, code: &str) -> CloudResult<CloudRedeemResponse> {
        let _ = self.signed_in()?;
        let endpoint = self.endpoint("api/console/redeem");
        let resp = self.client.redeem_card(&endpoint, code.trim()).await?;
        // Refresh local user snapshot so UI immediately reflects updated quota entitlements
        let _ = self.refresh_user().await;
        Ok(resp)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::Session;
    use crate::models::CloudUser;
    use messenger_store::Store;

    #[tokio::test]
    async fn preview_and_redeem_card() {
        let server = wiremock::MockServer::start().await;
        let store = Store::open_memory().unwrap();
        let engine = SyncEngine::new(&store, Session::default());
        engine.save_user(&CloudUser { id: "u1".into(), ..Default::default() }).unwrap();
        store.kv_set("cloud_server_url", server.uri().as_str()).unwrap();

        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/api/console/cards/preview"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "card": {
                    "code": "MS-ABC",
                    "planName": "Pro",
                    "quotaTokens": 100000,
                    "validityDays": 30
                }
            })))
            .mount(&server)
            .await;

        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/api/console/redeem"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "redemption": {
                    "cardCode": "MS-ABC",
                    "planName": "Pro",
                    "quotaTokens": 100000,
                    "validityDays": 30,
                    "redeemedAt": 12345
                },
                "quota": {
                    "balance": 100000,
                    "expiresAt": 12345i64 + 30i64 * 86_400_000i64
                }
            })))
            .mount(&server)
            .await;

        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/api/auth/me"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "user": {
                    "id": "u1",
                    "email": "a@b.c",
                    "quotaBalance": 100000
                }
            })))
            .mount(&server)
            .await;

        let preview = engine.preview_redeem_card("MS-ABC").await.unwrap();
        assert_eq!(preview.plan_name, "Pro");
        assert_eq!(preview.quota_tokens, 100000);

        let redeem = engine.redeem_card("MS-ABC").await.unwrap();
        assert_eq!(redeem.redemption.card_code, "MS-ABC");
        assert_eq!(engine.current_user().unwrap().quota_balance, Some(100000));
    }
}
