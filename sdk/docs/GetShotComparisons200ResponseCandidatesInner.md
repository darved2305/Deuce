
# GetShotComparisons200ResponseCandidatesInner


## Properties

Name | Type
------------ | -------------
`shotId` | string
`clipId` | [GetMatch200ResponseRalliesInnerClipId](GetMatch200ResponseRalliesInnerClipId.md)
`distance` | number
`matchedFeatures` | Array&lt;string&gt;
`role` | string
`evidence` | [GetShotComparisons200ResponseCandidatesInnerEvidence](GetShotComparisons200ResponseCandidatesInnerEvidence.md)
`provenance` | string
`attribution` | any

## Example

```typescript
import type { GetShotComparisons200ResponseCandidatesInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "shotId": null,
  "clipId": null,
  "distance": null,
  "matchedFeatures": null,
  "role": null,
  "evidence": null,
  "provenance": null,
  "attribution": null,
} satisfies GetShotComparisons200ResponseCandidatesInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetShotComparisons200ResponseCandidatesInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


