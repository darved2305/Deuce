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
import type { AbortUploadRequest } from '@elevate/sdk';

async function example() {
  console.log("🚀 Testing @elevate/sdk SDK...");
  const api = new DefaultApi();

  const body = {
    // string
    id: id_example,
  } satisfies AbortUploadRequest;

  try {
    const data = await api.abortUpload(body);
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
*DefaultApi* | [**abortUpload**](docs/DefaultApi.md#abortupload) | **POST** /v1/sessions/{id}/upload/abort | 
*DefaultApi* | [**acceptChallenge**](docs/DefaultApi.md#acceptchallengeoperation) | **POST** /v1/challenges/{id}/accept | 
*DefaultApi* | [**completeSegment**](docs/DefaultApi.md#completesegment) | **POST** /v1/sessions/{id}/segments/{seq}/complete | 
*DefaultApi* | [**completeUpload**](docs/DefaultApi.md#completeuploadoperation) | **POST** /v1/sessions/{id}/upload/complete | 
*DefaultApi* | [**confirmReview**](docs/DefaultApi.md#confirmreviewoperation) | **POST** /v1/matches/{id}/reviews/{rvid}/confirm | 
*DefaultApi* | [**createAnalysis**](docs/DefaultApi.md#createanalysisoperation) | **POST** /v1/analyses | 
*DefaultApi* | [**createChallenge**](docs/DefaultApi.md#createchallengeoperation) | **POST** /v1/challenges | 
*DefaultApi* | [**createChallengeEntry**](docs/DefaultApi.md#createchallengeentryoperation) | **POST** /v1/challenges/{id}/entries | 
*DefaultApi* | [**createCorrection**](docs/DefaultApi.md#createcorrectionoperation) | **POST** /v1/matches/{id}/corrections | 
*DefaultApi* | [**createDeveloperKey**](docs/DefaultApi.md#createdeveloperkey) | **POST** /v1/developer-keys | 
*DefaultApi* | [**createSession**](docs/DefaultApi.md#createsessionoperation) | **POST** /v1/sessions | 
*DefaultApi* | [**createUploadParts**](docs/DefaultApi.md#createuploadpartsoperation) | **POST** /v1/sessions/{id}/upload/parts | 
*DefaultApi* | [**deleteMatch**](docs/DefaultApi.md#deletematch) | **DELETE** /v1/matches/{id} | 
*DefaultApi* | [**getAnalysis**](docs/DefaultApi.md#getanalysis) | **GET** /v1/analyses/{id} | 
*DefaultApi* | [**getArtifact**](docs/DefaultApi.md#getartifact) | **GET** /v1/artifacts/{id} | 
*DefaultApi* | [**getCapabilities**](docs/DefaultApi.md#getcapabilities) | **GET** /v1/capabilities | 
*DefaultApi* | [**getChallenge**](docs/DefaultApi.md#getchallenge) | **GET** /v1/challenges/{id} | 
*DefaultApi* | [**getHealth**](docs/DefaultApi.md#gethealth) | **GET** /v1/health | 
*DefaultApi* | [**getMatch**](docs/DefaultApi.md#getmatch) | **GET** /v1/matches/{id} | 
*DefaultApi* | [**getRally**](docs/DefaultApi.md#getrally) | **GET** /v1/matches/{id}/rallies/{rid} | 
*DefaultApi* | [**getSession**](docs/DefaultApi.md#getsession) | **GET** /v1/sessions/{id} | 
*DefaultApi* | [**getShotComparisons**](docs/DefaultApi.md#getshotcomparisons) | **GET** /v1/matches/{mid}/shots/{shot_id}/comparisons | 
*DefaultApi* | [**listAnalysisEvents**](docs/DefaultApi.md#listanalysisevents) | **GET** /v1/analyses/{id}/events | 
*DefaultApi* | [**listFriends**](docs/DefaultApi.md#listfriends) | **GET** /v1/friends | 
*DefaultApi* | [**listMatchMoments**](docs/DefaultApi.md#listmatchmoments) | **GET** /v1/matches/{id}/moments | 
*DefaultApi* | [**listMatches**](docs/DefaultApi.md#listmatches) | **GET** /v1/matches | 
*DefaultApi* | [**listMoments**](docs/DefaultApi.md#listmoments) | **GET** /v1/moments | 
*DefaultApi* | [**listRallies**](docs/DefaultApi.md#listrallies) | **GET** /v1/matches/{id}/rallies | 
*DefaultApi* | [**registerSegment**](docs/DefaultApi.md#registersegmentoperation) | **POST** /v1/sessions/{id}/segments | 
*DefaultApi* | [**revokeDeveloperKey**](docs/DefaultApi.md#revokedeveloperkey) | **DELETE** /v1/developer-keys/{id} | 


### Models

- [AbortUpload200Response](docs/AbortUpload200Response.md)
- [AcceptChallengeRequest](docs/AcceptChallengeRequest.md)
- [CompleteSegment200Response](docs/CompleteSegment200Response.md)
- [CompleteUpload200Response](docs/CompleteUpload200Response.md)
- [CompleteUploadRequest](docs/CompleteUploadRequest.md)
- [CompleteUploadRequestPartsInner](docs/CompleteUploadRequestPartsInner.md)
- [ConfirmReviewRequest](docs/ConfirmReviewRequest.md)
- [CreateAnalysis200Response](docs/CreateAnalysis200Response.md)
- [CreateAnalysis403Response](docs/CreateAnalysis403Response.md)
- [CreateAnalysis403ResponseError](docs/CreateAnalysis403ResponseError.md)
- [CreateAnalysisRequest](docs/CreateAnalysisRequest.md)
- [CreateChallenge201Response](docs/CreateChallenge201Response.md)
- [CreateChallenge201ResponseCreator](docs/CreateChallenge201ResponseCreator.md)
- [CreateChallenge201ResponseEntriesInner](docs/CreateChallenge201ResponseEntriesInner.md)
- [CreateChallenge201ResponseViewer](docs/CreateChallenge201ResponseViewer.md)
- [CreateChallengeEntryRequest](docs/CreateChallengeEntryRequest.md)
- [CreateChallengeRequest](docs/CreateChallengeRequest.md)
- [CreateChallengeRequestRoundResultsInner](docs/CreateChallengeRequestRoundResultsInner.md)
- [CreateChallengeRequestRoundsInner](docs/CreateChallengeRequestRoundsInner.md)
- [CreateCorrectionRequest](docs/CreateCorrectionRequest.md)
- [CreateSession201Response](docs/CreateSession201Response.md)
- [CreateSession201ResponseAnyOf](docs/CreateSession201ResponseAnyOf.md)
- [CreateSession201ResponseAnyOf1](docs/CreateSession201ResponseAnyOf1.md)
- [CreateSession201ResponseAnyOf1Segments](docs/CreateSession201ResponseAnyOf1Segments.md)
- [CreateSession201ResponseAnyOfUpload](docs/CreateSession201ResponseAnyOfUpload.md)
- [CreateSessionRequest](docs/CreateSessionRequest.md)
- [CreateSessionRequestCalibrationInput](docs/CreateSessionRequestCalibrationInput.md)
- [CreateSessionRequestCalibrationInputAnyOf](docs/CreateSessionRequestCalibrationInputAnyOf.md)
- [CreateSessionRequestCalibrationInputAnyOf1](docs/CreateSessionRequestCalibrationInputAnyOf1.md)
- [CreateSessionRequestCalibrationInputAnyOfPointsPx](docs/CreateSessionRequestCalibrationInputAnyOfPointsPx.md)
- [CreateSessionRequestFreePlaySeed](docs/CreateSessionRequestFreePlaySeed.md)
- [CreateSessionRequestRallyCandidatesInner](docs/CreateSessionRequestRallyCandidatesInner.md)
- [CreateSessionRequestSource](docs/CreateSessionRequestSource.md)
- [CreateSessionRequestSourcePts](docs/CreateSessionRequestSourcePts.md)
- [CreateSessionRequestWhoIsMe](docs/CreateSessionRequestWhoIsMe.md)
- [CreateUploadParts200Response](docs/CreateUploadParts200Response.md)
- [CreateUploadParts200ResponsePartsInner](docs/CreateUploadParts200ResponsePartsInner.md)
- [CreateUploadPartsRequest](docs/CreateUploadPartsRequest.md)
- [GetAnalysis200Response](docs/GetAnalysis200Response.md)
- [GetArtifact200Response](docs/GetArtifact200Response.md)
- [GetCapabilities200Response](docs/GetCapabilities200Response.md)
- [GetHealth200Response](docs/GetHealth200Response.md)
- [GetMatch200Response](docs/GetMatch200Response.md)
- [GetMatch200ResponseAnalysis](docs/GetMatch200ResponseAnalysis.md)
- [GetMatch200ResponseAnalysisArtifacts](docs/GetMatch200ResponseAnalysisArtifacts.md)
- [GetMatch200ResponseCalibration](docs/GetMatch200ResponseCalibration.md)
- [GetMatch200ResponseCapabilities](docs/GetMatch200ResponseCapabilities.md)
- [GetMatch200ResponseCapabilitiesCourt](docs/GetMatch200ResponseCapabilitiesCourt.md)
- [GetMatch200ResponseClipsInner](docs/GetMatch200ResponseClipsInner.md)
- [GetMatch200ResponseClipsInnerLocal](docs/GetMatch200ResponseClipsInnerLocal.md)
- [GetMatch200ResponseClipsInnerRemote](docs/GetMatch200ResponseClipsInnerRemote.md)
- [GetMatch200ResponseConfirmedPointsInner](docs/GetMatch200ResponseConfirmedPointsInner.md)
- [GetMatch200ResponseCorrectionsInner](docs/GetMatch200ResponseCorrectionsInner.md)
- [GetMatch200ResponseCorrectionsInnerTarget](docs/GetMatch200ResponseCorrectionsInnerTarget.md)
- [GetMatch200ResponseEventsInner](docs/GetMatch200ResponseEventsInner.md)
- [GetMatch200ResponseEventsInnerEvidenceInner](docs/GetMatch200ResponseEventsInnerEvidenceInner.md)
- [GetMatch200ResponseInsightsInner](docs/GetMatch200ResponseInsightsInner.md)
- [GetMatch200ResponseInsightsInnerCount](docs/GetMatch200ResponseInsightsInnerCount.md)
- [GetMatch200ResponseInsightsInnerVisual](docs/GetMatch200ResponseInsightsInnerVisual.md)
- [GetMatch200ResponseInsightsInnerVisualFilter](docs/GetMatch200ResponseInsightsInnerVisualFilter.md)
- [GetMatch200ResponseMetricsInner](docs/GetMatch200ResponseMetricsInner.md)
- [GetMatch200ResponseMomentsInner](docs/GetMatch200ResponseMomentsInner.md)
- [GetMatch200ResponsePlayersInner](docs/GetMatch200ResponsePlayersInner.md)
- [GetMatch200ResponseRalliesInner](docs/GetMatch200ResponseRalliesInner.md)
- [GetMatch200ResponseRalliesInnerClipId](docs/GetMatch200ResponseRalliesInnerClipId.md)
- [GetMatch200ResponseReviewsInner](docs/GetMatch200ResponseReviewsInner.md)
- [GetMatch200ResponseRuleset](docs/GetMatch200ResponseRuleset.md)
- [GetMatch200ResponseScore](docs/GetMatch200ResponseScore.md)
- [GetMatch200ResponseScoreTimelineInner](docs/GetMatch200ResponseScoreTimelineInner.md)
- [GetMatch200ResponseShotsInner](docs/GetMatch200ResponseShotsInner.md)
- [GetMatch200ResponseShotsInnerChosen](docs/GetMatch200ResponseShotsInnerChosen.md)
- [GetMatch200ResponseShotsInnerContext](docs/GetMatch200ResponseShotsInnerContext.md)
- [GetMatch200ResponseShotsInnerIncoming](docs/GetMatch200ResponseShotsInnerIncoming.md)
- [GetMatch200ResponseShotsInnerOutcome](docs/GetMatch200ResponseShotsInnerOutcome.md)
- [GetMatch200ResponseShotsInnerQuality](docs/GetMatch200ResponseShotsInnerQuality.md)
- [GetMatch200ResponseShotsInnerRoles](docs/GetMatch200ResponseShotsInnerRoles.md)
- [GetMatch200ResponseShotsInnerRolesFocal](docs/GetMatch200ResponseShotsInnerRolesFocal.md)
- [GetRally200Response](docs/GetRally200Response.md)
- [GetSession200Response](docs/GetSession200Response.md)
- [GetSession200ResponseUpload](docs/GetSession200ResponseUpload.md)
- [GetShotComparisons200Response](docs/GetShotComparisons200Response.md)
- [GetShotComparisons200ResponseCandidatesInner](docs/GetShotComparisons200ResponseCandidatesInner.md)
- [GetShotComparisons200ResponseCandidatesInnerEvidence](docs/GetShotComparisons200ResponseCandidatesInnerEvidence.md)
- [ListAnalysisEvents200Response](docs/ListAnalysisEvents200Response.md)
- [ListAnalysisEvents200ResponseEventsInner](docs/ListAnalysisEvents200ResponseEventsInner.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf1](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf1.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf2](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf2.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf3](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf3.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf4](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf4.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf5](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf5.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf6](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf6.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf7](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf7.md)
- [ListAnalysisEvents200ResponseEventsInnerAnyOf8](docs/ListAnalysisEvents200ResponseEventsInnerAnyOf8.md)
- [ListFriends200Response](docs/ListFriends200Response.md)
- [ListFriends200ResponseItemsInner](docs/ListFriends200ResponseItemsInner.md)
- [ListMatchMoments200Response](docs/ListMatchMoments200Response.md)
- [ListMatchMoments200ResponseItemsInner](docs/ListMatchMoments200ResponseItemsInner.md)
- [ListMatches200Response](docs/ListMatches200Response.md)
- [ListMatches200ResponseItemsInner](docs/ListMatches200ResponseItemsInner.md)
- [ListMatches200ResponseItemsInnerScore](docs/ListMatches200ResponseItemsInnerScore.md)
- [ListRallies200Response](docs/ListRallies200Response.md)
- [RegisterSegment200Response](docs/RegisterSegment200Response.md)
- [RegisterSegmentRequest](docs/RegisterSegmentRequest.md)

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
