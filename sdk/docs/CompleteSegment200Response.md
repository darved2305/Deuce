
# CompleteSegment200Response


## Properties

Name | Type
------------ | -------------
`seq` | number
`ptsStartUs` | number
`ptsEndUs` | number
`startsOnSyncSample` | boolean
`bytes` | number
`sha256` | string
`_final` | boolean
`r2Key` | string
`completedAt` | Date

## Example

```typescript
import type { CompleteSegment200Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "seq": null,
  "ptsStartUs": null,
  "ptsEndUs": null,
  "startsOnSyncSample": null,
  "bytes": null,
  "sha256": null,
  "_final": null,
  "r2Key": null,
  "completedAt": null,
} satisfies CompleteSegment200Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CompleteSegment200Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


