
# V1MatchesIdGet200ResponseClipsInner


## Properties

Name | Type
------------ | -------------
`clipId` | [V1MatchesIdGet200ResponseRalliesInnerClipId](V1MatchesIdGet200ResponseRalliesInnerClipId.md)
`source` | string
`rights` | string
`owner` | string
`matchId` | string
`startMs` | number
`endMs` | number
`contactMs` | number
`remote` | [V1MatchesIdGet200ResponseClipsInnerRemote](V1MatchesIdGet200ResponseClipsInnerRemote.md)
`local` | [V1MatchesIdGet200ResponseClipsInnerLocal](V1MatchesIdGet200ResponseClipsInnerLocal.md)
`attribution` | any

## Example

```typescript
import type { V1MatchesIdGet200ResponseClipsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "clipId": null,
  "source": null,
  "rights": null,
  "owner": null,
  "matchId": null,
  "startMs": null,
  "endMs": null,
  "contactMs": null,
  "remote": null,
  "local": null,
  "attribution": null,
} satisfies V1MatchesIdGet200ResponseClipsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200ResponseClipsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


