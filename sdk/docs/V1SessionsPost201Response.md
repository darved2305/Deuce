
# V1SessionsPost201Response


## Properties

Name | Type
------------ | -------------
`sessionId` | string
`matchId` | string
`upload` | [V1SessionsPost201ResponseAnyOfUpload](V1SessionsPost201ResponseAnyOfUpload.md)
`uploadMode` | string
`segments` | [V1SessionsPost201ResponseAnyOf1Segments](V1SessionsPost201ResponseAnyOf1Segments.md)

## Example

```typescript
import type { V1SessionsPost201Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "sessionId": null,
  "matchId": null,
  "upload": null,
  "uploadMode": null,
  "segments": null,
} satisfies V1SessionsPost201Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1SessionsPost201Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


