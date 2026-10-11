
# ListMatches200ResponseItemsInner


## Properties

Name | Type
------------ | -------------
`matchId` | string
`sport` | string
`format` | string
`ruleset` | [GetMatch200ResponseRuleset](GetMatch200ResponseRuleset.md)
`createdAt` | Date
`state` | string
`score` | [ListMatches200ResponseItemsInnerScore](ListMatches200ResponseItemsInnerScore.md)

## Example

```typescript
import type { ListMatches200ResponseItemsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "matchId": null,
  "sport": null,
  "format": null,
  "ruleset": null,
  "createdAt": null,
  "state": null,
  "score": null,
} satisfies ListMatches200ResponseItemsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ListMatches200ResponseItemsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


