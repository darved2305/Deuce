
# V1MatchesIdGet200ResponseShotsInner


## Properties

Name | Type
------------ | -------------
`shotId` | string
`rallyId` | string
`index` | number
`playerId` | string
`contactMs` | number
`contactSigmaMs` | number
`phase` | string
`referencePlane` | string
`coordinateFrameVersion` | number
`roles` | [V1MatchesIdGet200ResponseShotsInnerRoles](V1MatchesIdGet200ResponseShotsInnerRoles.md)
`incoming` | [V1MatchesIdGet200ResponseShotsInnerIncoming](V1MatchesIdGet200ResponseShotsInnerIncoming.md)
`chosen` | [V1MatchesIdGet200ResponseShotsInnerChosen](V1MatchesIdGet200ResponseShotsInnerChosen.md)
`flightTimeMs` | number
`flightTimeState` | string
`context` | [V1MatchesIdGet200ResponseShotsInnerContext](V1MatchesIdGet200ResponseShotsInnerContext.md)
`outcome` | [V1MatchesIdGet200ResponseShotsInnerOutcome](V1MatchesIdGet200ResponseShotsInnerOutcome.md)
`quality` | [V1MatchesIdGet200ResponseShotsInnerQuality](V1MatchesIdGet200ResponseShotsInnerQuality.md)
`clipId` | [V1MatchesIdGet200ResponseRalliesInnerClipId](V1MatchesIdGet200ResponseRalliesInnerClipId.md)

## Example

```typescript
import type { V1MatchesIdGet200ResponseShotsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "shotId": null,
  "rallyId": null,
  "index": null,
  "playerId": null,
  "contactMs": null,
  "contactSigmaMs": null,
  "phase": null,
  "referencePlane": null,
  "coordinateFrameVersion": null,
  "roles": null,
  "incoming": null,
  "chosen": null,
  "flightTimeMs": null,
  "flightTimeState": null,
  "context": null,
  "outcome": null,
  "quality": null,
  "clipId": null,
} satisfies V1MatchesIdGet200ResponseShotsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200ResponseShotsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


