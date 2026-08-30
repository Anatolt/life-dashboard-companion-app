# One-time enrollment protocol (v1)

Life Dashboard can configure Health Connect and Screen Time from one pasteable string. The pasted
value contains only a short-lived, single-use code. Long-lived webhook credentials are returned in
an HTTPS response and stored in Android's Keystore-backed encrypted preferences.

## Enrollment string

```text
lifedashboard://enroll?endpoint=https%3A%2F%2Fdash.example%2Fapi%2Fv1%2Fenrollments%2Fexchange#code=ONE_TIME_OPAQUE_CODE
```

- `endpoint` is an absolute HTTPS URL without embedded credentials.
- `code` is in the URI fragment so it is not sent to a web server, reverse proxy, or discovery
  endpoint while handling the string. It must expire quickly and be accepted only once.
- Clients must not accept a long-lived API token in this URI.

The first version is deliberately explicit: there is no implicit `.well-known` lookup and no
redirect following between hosts. A backend can therefore expose the exchange endpoint wherever
its deployment requires.

## Exchange request

The client sends `POST endpoint` with `Content-Type: application/json` and no authorization header:

```json
{
  "protocol_version": 1,
  "code": "ONE_TIME_OPAQUE_CODE",
  "client": {
    "app_id": "life-dashboard-android",
    "app_version": "1.8.0",
    "installation_id": "550e8400-e29b-41d4-a716-446655440000",
    "platform": "android"
  },
  "requested_capabilities": ["health", "screen_time"]
}
```

`installation_id` is a random per-install UUID, not a hardware identifier. The battery companion
uses `app_id: "device-battery-android"` and requests `device_battery`.

The server must consume a code only when it can return a successful profile. Retrying a network
failure, HTTP 429, or HTTP 5xx with the same code must therefore be safe. The server should bind a
code to the expected account, allowed capabilities, expiration, and optionally the intended app.

## Successful response

HTTP 200 returns:

```json
{
  "protocol_version": 1,
  "profile": {
    "name": "My Life Dashboard",
    "default_delivery": {
      "webhook_urls": ["https://dash.example/api/v1/ingest"],
      "headers": {"Authorization": "Bearer LONG_LIVED_TOKEN"},
      "signing_secret": "OPTIONAL_HMAC_SECRET"
    },
    "capability_overrides": {
      "screen_time": {
        "webhook_urls": ["https://dash.example/api/v1/ingest/screen-time"]
      }
    }
  }
}
```

Supported capability names are `health`, `screen_time`, and `device_battery`. For every override:

- an omitted field inherits the field from `default_delivery`;
- a present field replaces that entire default field (headers are not merged);
- `[]`, `{}`, and `""` explicitly clear URLs, headers, and the signing secret respectively.

URLs must use HTTPS. Clients reject an unsupported protocol version, malformed response, embedded
URL credentials, and non-HTTPS delivery endpoints without changing the existing profile.

## Errors

Errors use the same JSON shape:

```json
{
  "error": {
    "code": "expired_code",
    "message": "This enrollment code has expired.",
    "retry_after_seconds": 30
  }
}
```

| HTTP | Code | Client behavior |
| --- | --- | --- |
| 400 | `invalid_request` | Fix/generate a new string |
| 401 or 404 | `invalid_code` | Do not retry automatically |
| 409 | `already_used` | Generate a new code |
| 410 | `expired_code` | Generate a new code |
| 422 | `unsupported_client` | Do not retry automatically |
| 429 | `rate_limited` | May retry after `retry_after_seconds` |
| 5xx | `server_error` | May retry the same unconsumed code |

Messages must be safe to display and must not echo the code or returned credentials.

## Existing installs and manual overrides

On upgrade, identical Health and Screen Time settings become the common profile automatically.
Different settings remain separate category overrides, preserving delivery behavior exactly. Editing
webhook settings inside a category creates/updates its override; turning the override off returns
that category to the common profile.
