
# V1MatchesIdGet200ResponseInsightsInner


## Properties

Name | Type
------------ | -------------
`insightId` | string
`kind` | string
`caption` | string
`count` | [V1MatchesIdGet200ResponseInsightsInnerCount](V1MatchesIdGet200ResponseInsightsInnerCount.md)
`visual` | [V1MatchesIdGet200ResponseInsightsInnerVisual](V1MatchesIdGet200ResponseInsightsInnerVisual.md)
`evidenceMomentIds` | Array&lt;string&gt;
`gate` | string

## Example

```typescript
import type { V1MatchesIdGet200ResponseInsightsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "insightId": null,
  "kind": null,
  "caption": null,
  "count": null,
  "visual": null,
  "evidenceMomentIds": null,
  "gate": null,
} satisfies V1MatchesIdGet200ResponseInsightsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200ResponseInsightsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


