
# V1MatchesIdGet200ResponseEventsInner


## Properties

Name | Type
------------ | -------------
`eventId` | string
`type` | string
`tMs` | number
`tSigmaMs` | number
`rallyId` | string
`playerId` | string
`trackId` | string
`plane` | string
`contactXyM` | Array&lt;number&gt;
`covM2` | Array&lt;Array&lt;number&gt;&gt;
`imageXy` | Array&lt;number&gt;
`cues` | Array&lt;string&gt;
`state` | string
`evidence` | [Array&lt;V1MatchesIdGet200ResponseEventsInnerEvidenceInner&gt;](V1MatchesIdGet200ResponseEventsInnerEvidenceInner.md)

## Example

```typescript
import type { V1MatchesIdGet200ResponseEventsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "eventId": null,
  "type": null,
  "tMs": null,
  "tSigmaMs": null,
  "rallyId": null,
  "playerId": null,
  "trackId": null,
  "plane": null,
  "contactXyM": null,
  "covM2": null,
  "imageXy": null,
  "cues": null,
  "state": null,
  "evidence": null,
} satisfies V1MatchesIdGet200ResponseEventsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200ResponseEventsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


