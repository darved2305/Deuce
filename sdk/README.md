# @elevate/sdk@0.1.0

A TypeScript SDK client for the zpwxjjsugujmihhkmfjx.supabase.co API.

## Usage

First, install the SDK from npm.

```bash
npm install @elevate/sdk --save
```

Next, try it out.


```ts
import {
  Configuration,
  DefaultApi,
} from '@elevate/sdk';
import type { V1AnalysesIdEventsGetRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
    // number (optional)
    afterSeq: 56,
    // number (optional)
    limit: 56,
  } satisfies V1AnalysesIdEventsGetRequest;

  try {
    const data = await api.v1AnalysesIdEventsGet(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```


## Documentation

### API Endpoints

All URIs are relative to *https://zpwxjjsugujmihhkmfjx.supabase.co/functions/v1/api*

| Class | Method | HTTP request | Description
| ----- | ------ | ------------ | -------------
*DefaultApi* | [**v1AnalysesIdEventsGet**](docs/DefaultApi.md#v1analysesideventsget) | **GET** /v1/analyses/{id}/events | 
*DefaultApi* | [**v1AnalysesIdGet**](docs/DefaultApi.md#v1analysesidget) | **GET** /v1/analyses/{id} | 
*DefaultApi* | [**v1AnalysesPost**](docs/DefaultApi.md#v1analysespostoperation) | **POST** /v1/analyses | 
*DefaultApi* | [**v1ArtifactsIdGet**](docs/DefaultApi.md#v1artifactsidget) | **GET** /v1/artifacts/{id} | 
*DefaultApi* | [**v1HealthGet**](docs/DefaultApi.md#v1healthget) | **GET** /v1/health | 
*DefaultApi* | [**v1MatchesIdGet**](docs/DefaultApi.md#v1matchesidget) | **GET** /v1/matches/{id} | 
*DefaultApi* | [**v1MatchesIdReviewsRvidConfirmPost**](docs/DefaultApi.md#v1matchesidreviewsrvidconfirmpostoperation) | **POST** /v1/matches/{id}/reviews/{rvid}/confirm | 
*DefaultApi* | [**v1SessionsIdGet**](docs/DefaultApi.md#v1sessionsidget) | **GET** /v1/sessions/{id} | 
*DefaultApi* | [**v1SessionsIdUploadAbortPost**](docs/DefaultApi.md#v1sessionsiduploadabortpost) | **POST** /v1/sessions/{id}/upload/abort | 
*DefaultApi* | [**v1SessionsIdUploadCompletePost**](docs/DefaultApi.md#v1sessionsiduploadcompletepostoperation) | **POST** /v1/sessions/{id}/upload/complete | 
*DefaultApi* | [**v1SessionsIdUploadPartsPost**](docs/DefaultApi.md#v1sessionsiduploadpartspostoperation) | **POST** /v1/sessions/{id}/upload/parts | 
*DefaultApi* | [**v1SessionsPost**](docs/DefaultApi.md#v1sessionspostoperation) | **POST** /v1/sessions | 


### Models

- [V1AnalysesIdEventsGet200Response](docs/V1AnalysesIdEventsGet200Response.md)
- [V1AnalysesIdEventsGet200ResponseEventsInner](docs/V1AnalysesIdEventsGet200ResponseEventsInner.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf1](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf1.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf2](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf2.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf3](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf3.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf4](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf4.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf5](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf5.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf6](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf6.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf7](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf7.md)
- [V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf8](docs/V1AnalysesIdEventsGet200ResponseEventsInnerAnyOf8.md)
- [V1AnalysesIdGet200Response](docs/V1AnalysesIdGet200Response.md)
- [V1AnalysesPost200Response](docs/V1AnalysesPost200Response.md)
- [V1AnalysesPost429Response](docs/V1AnalysesPost429Response.md)
- [V1AnalysesPost429ResponseError](docs/V1AnalysesPost429ResponseError.md)
- [V1AnalysesPostRequest](docs/V1AnalysesPostRequest.md)
- [V1ArtifactsIdGet200Response](docs/V1ArtifactsIdGet200Response.md)
- [V1HealthGet200Response](docs/V1HealthGet200Response.md)
- [V1MatchesIdGet200Response](docs/V1MatchesIdGet200Response.md)
- [V1MatchesIdGet200ResponseAnalysis](docs/V1MatchesIdGet200ResponseAnalysis.md)
- [V1MatchesIdGet200ResponseAnalysisArtifacts](docs/V1MatchesIdGet200ResponseAnalysisArtifacts.md)
- [V1MatchesIdGet200ResponseCalibration](docs/V1MatchesIdGet200ResponseCalibration.md)
- [V1MatchesIdGet200ResponseCapabilities](docs/V1MatchesIdGet200ResponseCapabilities.md)
- [V1MatchesIdGet200ResponseCapabilitiesCourt](docs/V1MatchesIdGet200ResponseCapabilitiesCourt.md)
- [V1MatchesIdGet200ResponseClipsInner](docs/V1MatchesIdGet200ResponseClipsInner.md)
- [V1MatchesIdGet200ResponseClipsInnerLocal](docs/V1MatchesIdGet200ResponseClipsInnerLocal.md)
- [V1MatchesIdGet200ResponseClipsInnerRemote](docs/V1MatchesIdGet200ResponseClipsInnerRemote.md)
- [V1MatchesIdGet200ResponseConfirmedPointsInner](docs/V1MatchesIdGet200ResponseConfirmedPointsInner.md)
- [V1MatchesIdGet200ResponseCorrectionsInner](docs/V1MatchesIdGet200ResponseCorrectionsInner.md)
- [V1MatchesIdGet200ResponseCorrectionsInnerTarget](docs/V1MatchesIdGet200ResponseCorrectionsInnerTarget.md)
- [V1MatchesIdGet200ResponseEventsInner](docs/V1MatchesIdGet200ResponseEventsInner.md)
- [V1MatchesIdGet200ResponseEventsInnerEvidenceInner](docs/V1MatchesIdGet200ResponseEventsInnerEvidenceInner.md)
- [V1MatchesIdGet200ResponseInsightsInner](docs/V1MatchesIdGet200ResponseInsightsInner.md)
- [V1MatchesIdGet200ResponseInsightsInnerCount](docs/V1MatchesIdGet200ResponseInsightsInnerCount.md)
- [V1MatchesIdGet200ResponseInsightsInnerVisual](docs/V1MatchesIdGet200ResponseInsightsInnerVisual.md)
- [V1MatchesIdGet200ResponseInsightsInnerVisualFilter](docs/V1MatchesIdGet200ResponseInsightsInnerVisualFilter.md)
- [V1MatchesIdGet200ResponseMetricsInner](docs/V1MatchesIdGet200ResponseMetricsInner.md)
- [V1MatchesIdGet200ResponseMomentsInner](docs/V1MatchesIdGet200ResponseMomentsInner.md)
- [V1MatchesIdGet200ResponsePlayersInner](docs/V1MatchesIdGet200ResponsePlayersInner.md)
- [V1MatchesIdGet200ResponseRalliesInner](docs/V1MatchesIdGet200ResponseRalliesInner.md)
- [V1MatchesIdGet200ResponseRalliesInnerClipId](docs/V1MatchesIdGet200ResponseRalliesInnerClipId.md)
- [V1MatchesIdGet200ResponseReviewsInner](docs/V1MatchesIdGet200ResponseReviewsInner.md)
- [V1MatchesIdGet200ResponseRuleset](docs/V1MatchesIdGet200ResponseRuleset.md)
- [V1MatchesIdGet200ResponseScore](docs/V1MatchesIdGet200ResponseScore.md)
- [V1MatchesIdGet200ResponseScoreTimelineInner](docs/V1MatchesIdGet200ResponseScoreTimelineInner.md)
- [V1MatchesIdGet200ResponseShotsInner](docs/V1MatchesIdGet200ResponseShotsInner.md)
- [V1MatchesIdGet200ResponseShotsInnerChosen](docs/V1MatchesIdGet200ResponseShotsInnerChosen.md)
- [V1MatchesIdGet200ResponseShotsInnerContext](docs/V1MatchesIdGet200ResponseShotsInnerContext.md)
- [V1MatchesIdGet200ResponseShotsInnerIncoming](docs/V1MatchesIdGet200ResponseShotsInnerIncoming.md)
- [V1MatchesIdGet200ResponseShotsInnerOutcome](docs/V1MatchesIdGet200ResponseShotsInnerOutcome.md)
- [V1MatchesIdGet200ResponseShotsInnerQuality](docs/V1MatchesIdGet200ResponseShotsInnerQuality.md)
- [V1MatchesIdGet200ResponseShotsInnerRoles](docs/V1MatchesIdGet200ResponseShotsInnerRoles.md)
- [V1MatchesIdGet200ResponseShotsInnerRolesFocal](docs/V1MatchesIdGet200ResponseShotsInnerRolesFocal.md)
- [V1MatchesIdReviewsRvidConfirmPostRequest](docs/V1MatchesIdReviewsRvidConfirmPostRequest.md)
- [V1SessionsIdGet200Response](docs/V1SessionsIdGet200Response.md)
- [V1SessionsIdGet200ResponseUpload](docs/V1SessionsIdGet200ResponseUpload.md)
- [V1SessionsIdUploadAbortPost200Response](docs/V1SessionsIdUploadAbortPost200Response.md)
- [V1SessionsIdUploadCompletePost200Response](docs/V1SessionsIdUploadCompletePost200Response.md)
- [V1SessionsIdUploadCompletePostRequest](docs/V1SessionsIdUploadCompletePostRequest.md)
- [V1SessionsIdUploadCompletePostRequestPartsInner](docs/V1SessionsIdUploadCompletePostRequestPartsInner.md)
- [V1SessionsIdUploadPartsPost200Response](docs/V1SessionsIdUploadPartsPost200Response.md)
- [V1SessionsIdUploadPartsPost200ResponsePartsInner](docs/V1SessionsIdUploadPartsPost200ResponsePartsInner.md)
- [V1SessionsIdUploadPartsPostRequest](docs/V1SessionsIdUploadPartsPostRequest.md)
- [V1SessionsPost201Response](docs/V1SessionsPost201Response.md)
- [V1SessionsPost201ResponseAnyOf](docs/V1SessionsPost201ResponseAnyOf.md)
- [V1SessionsPost201ResponseAnyOf1](docs/V1SessionsPost201ResponseAnyOf1.md)
- [V1SessionsPost201ResponseAnyOf1Segments](docs/V1SessionsPost201ResponseAnyOf1Segments.md)
- [V1SessionsPost201ResponseAnyOfUpload](docs/V1SessionsPost201ResponseAnyOfUpload.md)
- [V1SessionsPostRequest](docs/V1SessionsPostRequest.md)
- [V1SessionsPostRequestCalibrationInput](docs/V1SessionsPostRequestCalibrationInput.md)
- [V1SessionsPostRequestCalibrationInputAnyOf](docs/V1SessionsPostRequestCalibrationInputAnyOf.md)
- [V1SessionsPostRequestCalibrationInputAnyOf1](docs/V1SessionsPostRequestCalibrationInputAnyOf1.md)
- [V1SessionsPostRequestCalibrationInputAnyOfPointsPx](docs/V1SessionsPostRequestCalibrationInputAnyOfPointsPx.md)
- [V1SessionsPostRequestFreePlaySeed](docs/V1SessionsPostRequestFreePlaySeed.md)
- [V1SessionsPostRequestRallyCandidatesInner](docs/V1SessionsPostRequestRallyCandidatesInner.md)
- [V1SessionsPostRequestSource](docs/V1SessionsPostRequestSource.md)
- [V1SessionsPostRequestSourcePts](docs/V1SessionsPostRequestSourcePts.md)
- [V1SessionsPostRequestWhoIsMe](docs/V1SessionsPostRequestWhoIsMe.md)

### Authorization

Protected endpoints require a Supabase bearer JWT. Pass it as `new Configuration({ headers: { Authorization: "Bearer <access-token>" } })`; `/v1/health` is public.


## About

This TypeScript SDK client supports the [Fetch API](https://fetch.spec.whatwg.org/)
and is automatically generated by the
[OpenAPI Generator](https://openapi-generator.tech) project:

- API version: `0.1.0`
- Package version: `0.1.0`
- Generator version: `7.26.0`
- Build package: `org.openapitools.codegen.languages.TypeScriptFetchClientCodegen`

The generated npm module supports the following:

- Environments
  * Node.js
  * Webpack
  * Browserify
- Language levels
  * ES5 - you must have a Promises/A+ library installed
  * ES6
- Module systems
  * CommonJS
  * ES6 module system


## Development

### Building

To build the TypeScript source code, you need to have Node.js and npm installed.
After cloning the repository, navigate to the project directory and run:

```bash
npm install
npm run build
```

### Publishing

Once you've built the package, you can publish it to npm:

```bash
npm publish
```

## License

[]()
