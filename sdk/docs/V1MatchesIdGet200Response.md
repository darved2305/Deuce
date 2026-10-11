
# V1MatchesIdGet200Response


## Properties

Name | Type
------------ | -------------
`schema` | string
`matchId` | string
`sessionIds` | Array&lt;string&gt;
`sport` | string
`format` | string
`ruleset` | [V1MatchesIdGet200ResponseRuleset](V1MatchesIdGet200ResponseRuleset.md)
`players` | [Array&lt;V1MatchesIdGet200ResponsePlayersInner&gt;](V1MatchesIdGet200ResponsePlayersInner.md)
`calibration` | [V1MatchesIdGet200ResponseCalibration](V1MatchesIdGet200ResponseCalibration.md)
`analysis` | [V1MatchesIdGet200ResponseAnalysis](V1MatchesIdGet200ResponseAnalysis.md)
`capabilities` | [V1MatchesIdGet200ResponseCapabilities](V1MatchesIdGet200ResponseCapabilities.md)
`rallies` | [Array&lt;V1MatchesIdGet200ResponseRalliesInner&gt;](V1MatchesIdGet200ResponseRalliesInner.md)
`shots` | [Array&lt;V1MatchesIdGet200ResponseShotsInner&gt;](V1MatchesIdGet200ResponseShotsInner.md)
`events` | [Array&lt;V1MatchesIdGet200ResponseEventsInner&gt;](V1MatchesIdGet200ResponseEventsInner.md)
`reviews` | [Array&lt;V1MatchesIdGet200ResponseReviewsInner&gt;](V1MatchesIdGet200ResponseReviewsInner.md)
`confirmedPoints` | [Array&lt;V1MatchesIdGet200ResponseConfirmedPointsInner&gt;](V1MatchesIdGet200ResponseConfirmedPointsInner.md)
`score` | [V1MatchesIdGet200ResponseScore](V1MatchesIdGet200ResponseScore.md)
`metrics` | [Array&lt;V1MatchesIdGet200ResponseMetricsInner&gt;](V1MatchesIdGet200ResponseMetricsInner.md)
`insights` | [Array&lt;V1MatchesIdGet200ResponseInsightsInner&gt;](V1MatchesIdGet200ResponseInsightsInner.md)
`moments` | [Array&lt;V1MatchesIdGet200ResponseMomentsInner&gt;](V1MatchesIdGet200ResponseMomentsInner.md)
`clips` | [Array&lt;V1MatchesIdGet200ResponseClipsInner&gt;](V1MatchesIdGet200ResponseClipsInner.md)
`corrections` | [Array&lt;V1MatchesIdGet200ResponseCorrectionsInner&gt;](V1MatchesIdGet200ResponseCorrectionsInner.md)

## Example

```typescript
import type { V1MatchesIdGet200Response } from '@elevate/sdk'

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
} satisfies V1MatchesIdGet200Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


