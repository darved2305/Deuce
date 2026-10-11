
# GetMatch200ResponseClipsInner


## Properties

Name | Type
------------ | -------------
`clipId` | [GetMatch200ResponseRalliesInnerClipId](GetMatch200ResponseRalliesInnerClipId.md)
`source` | string
`rights` | string
`owner` | string
`matchId` | string
`startMs` | number
`endMs` | number
`contactMs` | number
`remote` | [GetMatch200ResponseClipsInnerRemote](GetMatch200ResponseClipsInnerRemote.md)
`local` | [GetMatch200ResponseClipsInnerLocal](GetMatch200ResponseClipsInnerLocal.md)
`attribution` | any

## Example

```typescript
import type { GetMatch200ResponseClipsInner } from '@elevate/sdk'

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
} satisfies GetMatch200ResponseClipsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetMatch200ResponseClipsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


