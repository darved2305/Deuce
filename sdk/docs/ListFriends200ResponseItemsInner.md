
# ListFriends200ResponseItemsInner


## Properties

Name | Type
------------ | -------------
`playerId` | string
`displayName` | string
`challenges` | number
`played` | number
`wins` | number
`losses` | number
`ties` | number
`lastChallengeAt` | Date

## Example

```typescript
import type { ListFriends200ResponseItemsInner } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "playerId": null,
  "displayName": null,
  "challenges": null,
  "played": null,
  "wins": null,
  "losses": null,
  "ties": null,
  "lastChallengeAt": null,
} satisfies ListFriends200ResponseItemsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ListFriends200ResponseItemsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


