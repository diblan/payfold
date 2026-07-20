{
  "priority": 1,
  "request": {
    "method": "POST",
    "urlPath": "/psp/charges",
    "bodyPatterns": [
      {
        "matchesJsonPath": {
          "expression": "$.subscription_id",
          "matches": "^.*[__PSP_FAIL_HEX__]$"
        }
      }
    ]
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "status": "declined", "reason": "card_declined" }
  }
}
