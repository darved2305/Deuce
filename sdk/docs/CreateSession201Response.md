
# CreateSession201Response


## Properties

Name | Type
------------ | -------------
`sessionId` | string
`matchId` | string
`upload` | [CreateSession201ResponseAnyOfUpload](CreateSession201ResponseAnyOfUpload.md)
`uploadMode` | string
`segments` | [CreateSession201ResponseAnyOf1Segments](CreateSession201ResponseAnyOf1Segments.md)

## Example

```typescript
import type { CreateSession201Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "sessionId": null,
  "matchId": null,
  "upload": null,
  "uploadMode": null,
  "segments": null,
} satisfies CreateSession201Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateSession201Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


