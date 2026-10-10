# @elevate/contracts

`src/` is the single source of truth. It exports strict Zod 4 schemas and inferred TypeScript
types. `schemas/` contains generated JSON Schema draft 2020-12 files for Python and Kotlin.
Do not edit generated files by hand.

## TypeScript

Import schemas and types from the workspace package:

```ts
import { matchV1Schema, type MatchV1 } from "@elevate/contracts";

const matchToSend: MatchV1 = matchV1Schema.parse(matchRecord);
```

The schemas can also be passed to `@hono/zod-openapi` 1.6.3 (peer range `zod ^4.0.0`).

## Deno

Vendor `packages/contracts/src/` into `supabase/functions/api/lib/contracts_vendor/`. The source
uses explicit `.ts` relative imports and has no Node APIs. Map its only external import, `zod`, to
the pinned npm package:

```json
{
  "imports": {
    "zod": "npm:zod@4.6.5"
  }
}
```

## Python

Validate produced data with the generated schema:

```python
import json
from jsonschema import Draft202012Validator, FormatChecker

schema = json.load(open("packages/contracts/schemas/match.v1.schema.json"))
Draft202012Validator.check_schema(schema)
Draft202012Validator(schema, format_checker=FormatChecker()).validate(match_record)
```

## Rules JSON Schema cannot express

Zod enforces three cross-field rules that the generated JSON Schema drops. Python and Kotlin
producers check them by hand:

1. Every `[start, end]` range (`*_us`, `*_ms` pairs) has `start <= end`.
2. A badminton session whose `calibration_input.plane` is `court` uses template
   `badminton_<format>` (§2).
3. A match event with `contact_xy_m` also has `plane` (§0: surface points only).

```python
def ranges_ordered(value):
    if isinstance(value, dict):
        return all(ranges_ordered(v) and (not k.endswith(("_us", "_ms")) or not isinstance(v, list)
                   or len(v) != 2 or v[0] <= v[1]) for k, v in value.items())
    return all(ranges_ordered(v) for v in value) if isinstance(value, list) else True
```

## Kotlin

Generate or validate models from `schemas/*.schema.json`. API response parsers should set
`ignoreUnknownKeys = true`, so optional additions do not break deployed readers:

```kotlin
val json = Json { ignoreUnknownKeys = true }
```

Use the strict schemas to validate objects before sending. API response readers use permissive
parsers that ignore fields they do not know; do not validate received responses with strict
producer schemas.

## Evolution

- Additive changes, including new optional fields, bump the package minor version and keep the
  existing `.v1` schema name. Add enum values only when readers tolerate them.
- A removal, rename, or type change adds a `.v2` schema beside `.v1` until every lane has migrated.
- Every contract change needs a `DECISIONS.md` entry and the sign-off required by D-012.
