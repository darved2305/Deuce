
# GetFriendFeed200Response


## Properties

Name | Type
------------ | -------------
`items` | [Array&lt;CreateMatchPost201Response&gt;](CreateMatchPost201Response.md)
`nextCursor` | string

## Example

```typescript
import type { GetFriendFeed200Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "items": null,
  "nextCursor": null,
} satisfies GetFriendFeed200Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetFriendFeed200Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


