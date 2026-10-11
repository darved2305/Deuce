
# GetSession200ResponseUpload


## Properties

Name | Type
------------ | -------------
`status` | string
`partSize` | number
`bytes` | number
`partCount` | number
`completedAt` | Date

## Example

```typescript
import type { GetSession200ResponseUpload } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "status": null,
  "partSize": null,
  "bytes": null,
  "partCount": null,
  "completedAt": null,
} satisfies GetSession200ResponseUpload

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetSession200ResponseUpload
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


