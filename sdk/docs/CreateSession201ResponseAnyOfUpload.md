
# CreateSession201ResponseAnyOfUpload


## Properties

Name | Type
------------ | -------------
`uploadId` | string
`r2Key` | string
`partSize` | number
`maxConcurrency` | number

## Example

```typescript
import type { CreateSession201ResponseAnyOfUpload } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "uploadId": null,
  "r2Key": null,
  "partSize": null,
  "maxConcurrency": null,
} satisfies CreateSession201ResponseAnyOfUpload

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateSession201ResponseAnyOfUpload
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


