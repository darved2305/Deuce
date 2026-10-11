
# CreateSession201ResponseAnyOf


## Properties

Name | Type
------------ | -------------
`sessionId` | string
`matchId` | string
`upload` | [CreateSession201ResponseAnyOfUpload](CreateSession201ResponseAnyOfUpload.md)

## Example

```typescript
import type { CreateSession201ResponseAnyOf } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "sessionId": null,
  "matchId": null,
  "upload": null,
} satisfies CreateSession201ResponseAnyOf

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateSession201ResponseAnyOf
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


