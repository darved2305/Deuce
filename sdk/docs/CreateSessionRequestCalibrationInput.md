
# CreateSessionRequestCalibrationInput


## Properties

Name | Type
------------ | -------------
`method` | string
`plane` | string
`template` | string
`pointsPx` | [CreateSessionRequestCalibrationInputAnyOfPointsPx](CreateSessionRequestCalibrationInputAnyOfPointsPx.md)
`frameMs` | number

## Example

```typescript
import type { CreateSessionRequestCalibrationInput } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "method": null,
  "plane": null,
  "template": null,
  "pointsPx": null,
  "frameMs": null,
} satisfies CreateSessionRequestCalibrationInput

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CreateSessionRequestCalibrationInput
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


