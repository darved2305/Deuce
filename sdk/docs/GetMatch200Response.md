
# GetMatch200Response


## Properties

Name | Type
------------ | -------------
`schema` | string
`matchId` | string
`sessionIds` | Array&lt;string&gt;
`sport` | string
`format` | string
`ruleset` | [GetMatch200ResponseRuleset](GetMatch200ResponseRuleset.md)
`players` | [Array&lt;GetMatch200ResponsePlayersInner&gt;](GetMatch200ResponsePlayersInner.md)
`calibration` | [GetMatch200ResponseCalibration](GetMatch200ResponseCalibration.md)
`analysis` | [GetMatch200ResponseAnalysis](GetMatch200ResponseAnalysis.md)
`capabilities` | [GetMatch200ResponseCapabilities](GetMatch200ResponseCapabilities.md)
`rallies` | [Array&lt;GetMatch200ResponseRalliesInner&gt;](GetMatch200ResponseRalliesInner.md)
`shots` | [Array&lt;GetMatch200ResponseShotsInner&gt;](GetMatch200ResponseShotsInner.md)
`events` | [Array&lt;GetMatch200ResponseEventsInner&gt;](GetMatch200ResponseEventsInner.md)
`reviews` | [Array&lt;GetMatch200ResponseReviewsInner&gt;](GetMatch200ResponseReviewsInner.md)
`confirmedPoints` | [Array&lt;GetMatch200ResponseConfirmedPointsInner&gt;](GetMatch200ResponseConfirmedPointsInner.md)
`score` | [GetMatch200ResponseScore](GetMatch200ResponseScore.md)
`metrics` | [Array&lt;GetMatch200ResponseMetricsInner&gt;](GetMatch200ResponseMetricsInner.md)
`insights` | [Array&lt;GetMatch200ResponseInsightsInner&gt;](GetMatch200ResponseInsightsInner.md)
`moments` | [Array&lt;GetMatch200ResponseMomentsInner&gt;](GetMatch200ResponseMomentsInner.md)
`clips` | [Array&lt;GetMatch200ResponseClipsInner&gt;](GetMatch200ResponseClipsInner.md)
`corrections` | [Array&lt;GetMatch200ResponseCorrectionsInner&gt;](GetMatch200ResponseCorrectionsInner.md)

## Example

```typescript
import type { GetMatch200Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "schema": null,
  "matchId": null,
  "sessionIds": null,
  "sport": null,
  "format": null,
  "ruleset": null,
  "players": null,
  "calibration": null,
  "analysis": null,
  "capabilities": null,
  "rallies": null,
  "shots": null,
  "events": null,
  "reviews": null,
  "confirmedPoints": null,
  "score": null,
  "metrics": null,
  "insights": null,
  "moments": null,
  "clips": null,
  "corrections": null,
} satisfies GetMatch200Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetMatch200Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


