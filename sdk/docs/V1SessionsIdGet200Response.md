
# V1SessionsIdGet200Response


## Properties

Name | Type
------------ | -------------
`sessionId` | string
`matchId` | string
`mode` | string
`uploadMode` | string
`source` | [V1SessionsPostRequestSource](V1SessionsPostRequestSource.md)
`createdAt` | Date
`upload` | [V1SessionsIdGet200ResponseUpload](V1SessionsIdGet200ResponseUpload.md)

## Example

```typescript
import type { V1SessionsIdGet200Response } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "sessionId": null,
  "matchId": null,
  "mode": null,
  "uploadMode": null,
  "source": null,
  "createdAt": null,
  "upload": null,
} satisfies V1SessionsIdGet200Response

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1SessionsIdGet200Response
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


