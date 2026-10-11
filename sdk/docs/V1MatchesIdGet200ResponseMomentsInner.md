
# V1MatchesIdGet200ResponseMomentsInner


## Properties

Name | Type
------------ | -------------
`momentId` | string
`rallyId` | string
`shotId` | string
`startMs` | number
`endMs` | number
`contactMs` | number
`tags` | Array&lt;string&gt;
`clipId` | [V1MatchesIdGet200ResponseRalliesInnerClipId](V1MatchesIdGet200ResponseRalliesInnerClipId.md)

## Example

```typescript
import type { V1MatchesIdGet200ResponseMomentsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "momentId": null,
  "rallyId": null,
  "shotId": null,
  "startMs": null,
  "endMs": null,
  "contactMs": null,
  "tags": null,
  "clipId": null,
} satisfies V1MatchesIdGet200ResponseMomentsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200ResponseMomentsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


