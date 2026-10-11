
# GetMatch200ResponseCapabilities


## Properties

Name | Type
------------ | -------------
`court` | [GetMatch200ResponseCapabilitiesCourt](GetMatch200ResponseCapabilitiesCourt.md)
`ball` | [GetMatch200ResponseCapabilitiesCourt](GetMatch200ResponseCapabilitiesCourt.md)
`reviews` | [GetMatch200ResponseCapabilitiesCourt](GetMatch200ResponseCapabilitiesCourt.md)
`score` | [GetMatch200ResponseCapabilitiesCourt](GetMatch200ResponseCapabilitiesCourt.md)

## Example

```typescript
import type { GetMatch200ResponseCapabilities } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "court": null,
  "ball": null,
  "reviews": null,
  "score": null,
} satisfies GetMatch200ResponseCapabilities

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GetMatch200ResponseCapabilities
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


