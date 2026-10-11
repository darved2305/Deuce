
# V1MatchesIdGet200ResponseCalibration


## Properties

Name | Type
------------ | -------------
`calibrationId` | string
`plane` | string
`template` | string
`method` | string
`h` | Array&lt;number&gt;
`hCov` | Array&lt;number&gt;
`residualPx` | number
`visibleFraction` | number
`tier` | string

## Example

```typescript
import type { V1MatchesIdGet200ResponseCalibration } from '@elevate/sdk'

// TODO: Update the object below with actual values
const example = {
  "calibrationId": null,
  "plane": null,
  "template": null,
  "method": null,
  "h": null,
  "hCov": null,
  "residualPx": null,
  "visibleFraction": null,
  "tier": null,
} satisfies V1MatchesIdGet200ResponseCalibration

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as V1MatchesIdGet200ResponseCalibration
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


