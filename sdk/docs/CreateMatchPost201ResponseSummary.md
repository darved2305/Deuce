
# CreateMatchPost201ResponseSummary


## Properties

Name | Type
------------ | -------------
`title` | string
`durationMs` | number
`rallyCount` | number
`hitCount` | number
`landingsIn` | number
`landingsOut` | number
`longestRallyMs` | number
`topCoachPoints` | Array&lt;string&gt;
`landingPointsM` | [Array&lt;CreateMatchPost201ResponseSummaryLandingPointsMInner&gt;](CreateMatchPost201ResponseSummaryLandingPointsMInner.md)
`ownerDisplayName` | string

## Example

```typescript
import type { CreateMatchPost201ResponseSummary } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "title": null,
  "durationMs": null,
  "rallyCount": null,
  "hitCount": null,
  "landingsIn": null,
  "landingsOut": null,
  "longestRallyMs": null,
  "topCoachPoints": null,
  "landingPointsM": null,
  "ownerDisplayName": null,
} satisfies CreateMatchPost201ResponseSummary

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateMatchPost201ResponseSummary
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


