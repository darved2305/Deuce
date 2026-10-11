
# GetMatch200ResponseShotsInner


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
`roles` | [GetMatch200ResponseShotsInnerRoles](GetMatch200ResponseShotsInnerRoles.md)
`incoming` | [GetMatch200ResponseShotsInnerIncoming](GetMatch200ResponseShotsInnerIncoming.md)
`chosen` | [GetMatch200ResponseShotsInnerChosen](GetMatch200ResponseShotsInnerChosen.md)
`flightTimeMs` | number
`flightTimeState` | string
`context` | [GetMatch200ResponseShotsInnerContext](GetMatch200ResponseShotsInnerContext.md)
`outcome` | [GetMatch200ResponseShotsInnerOutcome](GetMatch200ResponseShotsInnerOutcome.md)
`quality` | [GetMatch200ResponseShotsInnerQuality](GetMatch200ResponseShotsInnerQuality.md)
`clipId` | [GetMatch200ResponseRalliesInnerClipId](GetMatch200ResponseRalliesInnerClipId.md)

## Example

```typescript
import type { GetMatch200ResponseShotsInner } from '@elevate/sdk'

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
} satisfies GetMatch200ResponseShotsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetMatch200ResponseShotsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


