
# GetMatch200ResponseRalliesInner


## Properties

Name | Type
------------ | -------------
`rallyId` | string
`startMs` | number
`endMs` | number
`shotIds` | Array&lt;string&gt;
`server` | string
`winner` | string
`winnerState` | string
`endReason` | string
`ballCoverage` | number
`clipId` | [GetMatch200ResponseRalliesInnerClipId](GetMatch200ResponseRalliesInnerClipId.md)
`state` | string

## Example

```typescript
import type { GetMatch200ResponseRalliesInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "rallyId": null,
  "startMs": null,
  "endMs": null,
  "shotIds": null,
  "server": null,
  "winner": null,
  "winnerState": null,
  "endReason": null,
  "ballCoverage": null,
  "clipId": null,
  "state": null,
} satisfies GetMatch200ResponseRalliesInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetMatch200ResponseRalliesInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


