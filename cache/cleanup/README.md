# Cleanup workflow cache

`OfficialDirectoryCrawler.crawl`, `PostCrawlCleanup.run`, `SocialLinkPipeline.run`, and
`GoogleSavedPlacesCleanupWorkflow` write generated denomination candidates, evidence, decision audits, and pending
catalog checkpoints here. Human overrides and deterministic denomination rules remain committed under
`resources/cleanup/`; generated/LLM decisions in this directory are not canonical until accepted into the catalog.
