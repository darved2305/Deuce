
# GetMatch200ResponseReviewsInner


## Properties

Name | Type
------------ | -------------
`reviewId` | string
`matchId` | string
`rallyId` | string
`eventId` | string
`tMs` | number
`evidenceFrames` | Array&lt;number&gt;
`contactXyM` | Array&lt;number&gt;
`covM2` | Array&lt;Array&lt;number&gt;&gt;
`lineId` | string
`marginM` | number
`sigmaM` | number
`estimate` | string
`visibility` | string
`modelIds` | Array&lt;string&gt;
`calibrationId` | string
`confirmedPointWinner` | string
`confirmedBy` | string
`confirmedAt` | Date

## Example

```typescript
import type { GetMatch200ResponseReviewsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "reviewId": null,
  "matchId": null,
  "rallyId": null,
  "eventId": null,
  "tMs": null,
  "evidenceFrames": null,
  "contactXyM": null,
  "covM2": null,
  "lineId": null,
  "marginM": null,
  "sigmaM": null,
  "estimate": null,
  "visibility": null,
  "modelIds": null,
  "calibrationId": null,
  "confirmedPointWinner": null,
  "confirmedBy": null,
  "confirmedAt": null,
} satisfies GetMatch200ResponseReviewsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetMatch200ResponseReviewsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


