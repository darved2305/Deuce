
# V1MatchesIdGet200ResponseCorrectionsInner


## Properties

Name | Type
------------ | -------------
`correctionId` | string
`target` | [V1MatchesIdGet200ResponseCorrectionsInnerTarget](V1MatchesIdGet200ResponseCorrectionsInnerTarget.md)
`field` | string
`old` | any
`_new` | any
`actor` | string
`at` | Date
`reason` | string

## Example

```typescript
import type { V1MatchesIdGet200ResponseCorrectionsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "correctionId": null,
  "target": null,
  "field": null,
  "old": null,
  "_new": null,
  "actor": null,
  "at": null,
  "reason": null,
} satisfies V1MatchesIdGet200ResponseCorrectionsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200ResponseCorrectionsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


