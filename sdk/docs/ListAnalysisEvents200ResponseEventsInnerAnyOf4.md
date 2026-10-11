
# ListAnalysisEvents200ResponseEventsInnerAnyOf4


## Properties

Name | Type
------------ | -------------
`eventId` | string
`analysisId` | string
`matchId` | string
`seq` | number
`schemaVersion` | number
`engineVersion` | string
`createdAt` | Date
`windowsExpected` | number
`decodeWindowUs` | Array&lt;number&gt;
`emitRangeUs` | Array&lt;number&gt;
`artifactId` | string
`type` | string
`windowSeq` | number
`reason` | string

## Example

```typescript
import type { ListAnalysisEvents200ResponseEventsInnerAnyOf4 } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "eventId": null,
  "analysisId": null,
  "matchId": null,
  "seq": null,
  "schemaVersion": null,
  "engineVersion": null,
  "createdAt": null,
  "windowsExpected": null,
  "decodeWindowUs": null,
  "emitRangeUs": null,
  "artifactId": null,
  "type": null,
  "windowSeq": null,
  "reason": null,
} satisfies ListAnalysisEvents200ResponseEventsInnerAnyOf4

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ListAnalysisEvents200ResponseEventsInnerAnyOf4
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


