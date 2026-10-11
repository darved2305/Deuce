
# GetAnalysis200Response


## Properties

Name | Type
------------ | -------------
`analysisId` | string
`sessionId` | string
`matchId` | string
`state` | string
`engineVersion` | string
`lastSeq` | number
`failureReason` | string
`createdAt` | Date
`updatedAt` | Date
`finalizedAt` | Date

## Example

```typescript
import type { GetAnalysis200Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "analysisId": null,
  "sessionId": null,
  "matchId": null,
  "state": null,
  "engineVersion": null,
  "lastSeq": null,
  "failureReason": null,
  "createdAt": null,
  "updatedAt": null,
  "finalizedAt": null,
} satisfies GetAnalysis200Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetAnalysis200Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


