
# CreateMatchPost201Response


## Properties

Name | Type
------------ | -------------
`postId` | string
`matchId` | string
`owner` | [CreateMatchPost201ResponseOwner](CreateMatchPost201ResponseOwner.md)
`createdAt` | Date
`caption` | string
`summary` | [CreateMatchPost201ResponseSummary](CreateMatchPost201ResponseSummary.md)

## Example

```typescript
import type { CreateMatchPost201Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "postId": null,
  "matchId": null,
  "owner": null,
  "createdAt": null,
  "caption": null,
  "summary": null,
} satisfies CreateMatchPost201Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateMatchPost201Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


