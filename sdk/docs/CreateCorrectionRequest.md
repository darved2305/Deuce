
# CreateCorrectionRequest


## Properties

Name | Type
------------ | -------------
`target` | [GetMatch200ResponseCorrectionsInnerTarget](GetMatch200ResponseCorrectionsInnerTarget.md)
`field` | string
`old` | any
`_new` | any
`reason` | string

## Example

```typescript
import type { CreateCorrectionRequest } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "target": null,
  "field": null,
  "old": null,
  "_new": null,
  "reason": null,
} satisfies CreateCorrectionRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateCorrectionRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


