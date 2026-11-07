# Changelog

## [1.3.44](https://github.com/jacobsandersen/bastion/compare/v1.3.43...v1.3.44) (2026-11-07)


### Features

* **post:** add auto syndication targets that attach to every new post ([#122](https://github.com/jacobsandersen/bastion/issues/122)) ([7d4267d](https://github.com/jacobsandersen/bastion/commit/7d4267d5cb8ee55e0d40114de9ee6539853d689c))


### Bug Fixes

* **micropub:** include canonical url in q=source and list all non-deleted posts ([#123](https://github.com/jacobsandersen/bastion/issues/123)) ([213e208](https://github.com/jacobsandersen/bastion/commit/213e208c3f58fa153cf572c352973cf8e9e62f83))
* **microsub:** align with Microsub spec and stop webmentions to blocked users ([#120](https://github.com/jacobsandersen/bastion/issues/120)) ([1a52372](https://github.com/jacobsandersen/bastion/commit/1a52372d3b91ed68e99f7835583e9d6728087e36))

## [1.3.43](https://github.com/jacobsandersen/bastion/compare/v1.3.42...v1.3.43) (2026-11-05)


### Bug Fixes

* diversify websub topic urls from single support to supporting various topics ([#117](https://github.com/jacobsandersen/bastion/issues/117)) ([dac91c8](https://github.com/jacobsandersen/bastion/commit/dac91c83751f1470e98bd2103472c46b4eb6bcc1))
* **websub:** add ability to pluralize type names for feeds ([#119](https://github.com/jacobsandersen/bastion/issues/119)) ([8520c41](https://github.com/jacobsandersen/bastion/commit/8520c418f78933902a0e4d0704be4a0d43a427b3))

## [1.3.42](https://github.com/jacobsandersen/bastion/compare/v1.3.41...v1.3.42) (2026-11-05)


### Features

* Add microsub support ([#115](https://github.com/jacobsandersen/bastion/issues/115)) ([59c3182](https://github.com/jacobsandersen/bastion/commit/59c3182e7d3608f2aeaeaf549f2b713a8d290a8d))

## [1.3.41](https://github.com/jacobsandersen/bastion/compare/v1.3.40...v1.3.41) (2026-11-05)


### Features

* **webmention:** add Salmention threading for received replies ([#112](https://github.com/jacobsandersen/bastion/issues/112)) ([7721f13](https://github.com/jacobsandersen/bastion/commit/7721f133d4cbe836d56eb85bac258a8789beab53))


### Miscellaneous Chores

* add salmention learnings ([#114](https://github.com/jacobsandersen/bastion/issues/114)) ([ec99675](https://github.com/jacobsandersen/bastion/commit/ec996758b6dafb9f4bbcca0d91f86c6f88802b96))

## [1.3.40](https://github.com/jacobsandersen/bastion/compare/v1.3.39...v1.3.40) (2026-11-03)


### Bug Fixes

* update pagination to include a total field ([#110](https://github.com/jacobsandersen/bastion/issues/110)) ([42e4b41](https://github.com/jacobsandersen/bastion/commit/42e4b41d4c1da47ae4d9aece0f34c27dfe2638a9))

## [1.3.39](https://github.com/jacobsandersen/bastion/compare/v1.3.38...v1.3.39) (2026-11-03)


### Features

* always include webmention and syndication data on feed requests ([#108](https://github.com/jacobsandersen/bastion/issues/108)) ([95b9f0b](https://github.com/jacobsandersen/bastion/commit/95b9f0be032bd9989b9c309c246a632371d46a68))

## [1.3.38](https://github.com/jacobsandersen/bastion/compare/v1.3.37...v1.3.38) (2026-11-03)


### Features

* improve syndication format feature, allowing for customization per post type ([#106](https://github.com/jacobsandersen/bastion/issues/106)) ([593ea38](https://github.com/jacobsandersen/bastion/commit/593ea38a541423f54d59628b73aa78877074f4fa))

## [1.3.37](https://github.com/jacobsandersen/bastion/compare/v1.3.36...v1.3.37) (2026-11-03)


### Features

* **post:** make post type discovery and syndication formats declarative ([#103](https://github.com/jacobsandersen/bastion/issues/103)) ([7810bc7](https://github.com/jacobsandersen/bastion/commit/7810bc797ec6b4cb20fe7e4128cf61aacc0acd1e))


### Bug Fixes

* **ci:** pass image inputs when triggering release builds ([#101](https://github.com/jacobsandersen/bastion/issues/101)) ([d68f480](https://github.com/jacobsandersen/bastion/commit/d68f480d9faa16e768c588a62cf726aa9757251e))

## [1.3.36](https://github.com/jacobsandersen/bastion/compare/v1.3.35...v1.3.36) (2026-10-29)


### Features

* add durable event publication registry ([b21428e](https://github.com/jacobsandersen/bastion/commit/b21428e55af7010f1269b6262931ff76f803f0e1))
* **events:** add durable Modulith event publication registry ([27af9dc](https://github.com/jacobsandersen/bastion/commit/27af9dc761e8a79fb177dcd8a00c73e285eb6fcd))
* **events:** resubmit failed event publications ([90433f7](https://github.com/jacobsandersen/bastion/commit/90433f7dc0c6dc495eed4223ec321d1e6f69cee6))
* **post:** detect concurrent post updates ([05c70ff](https://github.com/jacobsandersen/bastion/commit/05c70ffbe5c6002cb0016fcaa0b89ba63cd8ce03))
* **post:** detect concurrent post updates ([3ccdb4c](https://github.com/jacobsandersen/bastion/commit/3ccdb4cc6f5326afd72b187a7bcbc36bc0c59324))
* support multi-instance scaling ([efad806](https://github.com/jacobsandersen/bastion/commit/efad8064ad3f2df3c98c680e6f5198e44a08c270))
* **syndication:** add claim-based dispatch and periodic reconciliation ([10a5694](https://github.com/jacobsandersen/bastion/commit/10a569435744a6ceb5aefa02986aa74ef19c5bb6))
* **webmention:** make submission limits global with Redis ([aafe5c7](https://github.com/jacobsandersen/bastion/commit/aafe5c76bc909d940e0bc9c194334863c243a932))


### Documentation

* document event publication registry ([f7a4512](https://github.com/jacobsandersen/bastion/commit/f7a451206ecf948dbd475ee55bec856ca91dcfd1))
* document multi-instance scaling ([4b3b753](https://github.com/jacobsandersen/bastion/commit/4b3b753399e5d92413f48096295e4f3d22fcacdb))
* update module boundary notes ([2ecf29c](https://github.com/jacobsandersen/bastion/commit/2ecf29ce72c9be63b6eb2275ac010eae6050a292))


### Miscellaneous Chores

* **ci:** publish images from the repository owner ([#99](https://github.com/jacobsandersen/bastion/issues/99)) ([4553bb8](https://github.com/jacobsandersen/bastion/commit/4553bb80114b8095d74e84e0875223ad9bbf9ccf))
* **jobrunr:** configure multi-instance worker settings ([29797b0](https://github.com/jacobsandersen/bastion/commit/29797b04e9f82b7ccf685e5376b4d1f952f2dc24))
* use jacobsandersen microformats2 packages ([#100](https://github.com/jacobsandersen/bastion/issues/100)) ([53c7e5b](https://github.com/jacobsandersen/bastion/commit/53c7e5b6af12af13b8c2fe388be247bddd1602bc))


### Code Refactoring

* **config:** own Micropub security wiring in its module ([543f5a2](https://github.com/jacobsandersen/bastion/commit/543f5a22d0a648a67baee82be4f1c1afb7927c30))
* **events:** run post-change listeners asynchronously ([6c6fa81](https://github.com/jacobsandersen/bastion/commit/6c6fa8129e4bfa4bd7bbbfa4cc06db3373858d83))
* **indieauth:** let modules own their scopes ([842b516](https://github.com/jacobsandersen/bastion/commit/842b516b75c7a541e79376aea9b44534aea332c0))
* **indieauth:** let modules own their scopes ([bbcf437](https://github.com/jacobsandersen/bastion/commit/bbcf437407e9edb49cd593f04ab8523b8209a2c9))
* **indieauth:** resolve only tokens for the configured identity ([a407902](https://github.com/jacobsandersen/bastion/commit/a40790261cf95ed01730f973cf742d3de70339eb))
* **media:** extract media storage into a media module ([7e715fa](https://github.com/jacobsandersen/bastion/commit/7e715fa3ec6414b982ad7a9fc083364984353332))
* **post:** apply updates through a protocol-neutral patch ([e10d522](https://github.com/jacobsandersen/bastion/commit/e10d522eab02967b935fce1dba2385eb90e8c1a0))
* **post:** neutralize metadata and source query contracts ([bfb6ff9](https://github.com/jacobsandersen/bastion/commit/bfb6ff9d64d835b5f341d54f2123a611bc2b0029))
* **post:** remove unused model and syndication contracts ([596cd6f](https://github.com/jacobsandersen/bastion/commit/596cd6f70965becff65acd57f979c307154a5eb8))
* tighten module boundaries and adapter ownership ([af1c660](https://github.com/jacobsandersen/bastion/commit/af1c66063579ee8729fdc443b3bdd587c142455b))
* **url:** move content URL matching into the shared url module ([ae5cd7e](https://github.com/jacobsandersen/bastion/commit/ae5cd7e88ea9098799a222ced43fdaa7285c6e1b))
* **url:** own content URL generation ([c39dbdb](https://github.com/jacobsandersen/bastion/commit/c39dbdb1bd251475b9d9afd06c1a256daf280c3d))
* **url:** own content URL generation ([8739a95](https://github.com/jacobsandersen/bastion/commit/8739a950087525e978887a4b2c97402cd8b60d12))
* **util:** move slugify provider to the shared util module ([94213f3](https://github.com/jacobsandersen/bastion/commit/94213f3f6bdce69c7847f5b1216350293d0b8884))

## [1.3.35](https://github.com/Marchland/bastion/compare/v1.3.34...v1.3.35) (2026-10-27)


### Features

* adopt Spring Modulith boundaries and CQRS contracts ([#89](https://github.com/Marchland/bastion/issues/89)) ([82b148d](https://github.com/Marchland/bastion/commit/82b148db3685123b8cc5892c1bdfbccb6ab2e511))

## [1.3.34](https://github.com/Marchland/bastion/compare/v1.3.33...v1.3.34) (2026-10-27)


### ⚠ BREAKING CHANGES

* Forge, Sigil, Beacon, and Conduit are no longer used; all protocol endpoints are served by Bastion again. The 2.x line is abandoned.
* Bastion content authority, projections, and cleanup (#75, #77)
* add q=properties micropub query ([#72](https://github.com/Marchland/bastion/issues/72))

### Features

* add extractors for webmention text and links ([9359f46](https://github.com/Marchland/bastion/commit/9359f461e7eb4cbd8237f082085add1bf9dc7b17))
* add generic microformats2 html parser ([33383b9](https://github.com/Marchland/bastion/commit/33383b968e576c5e91b166125bf1b5962d838aa8))
* add more filters for feed lookup, tighten post lookup, improve post response ([53b3d13](https://github.com/Marchland/bastion/commit/53b3d13759668a98ab9faf580dc2941b491e04f2))
* add q=properties micropub query ([#72](https://github.com/Marchland/bastion/issues/72)) ([ea839a5](https://github.com/Marchland/bastion/commit/ea839a5f95245975aacc3cbc701431d41c05d53f))
* add received webmention entity and repository ([96214da](https://github.com/Marchland/bastion/commit/96214da5e85f1413857f4148243ced6bc7ab89ea))
* add tag listing and tag-filtered post feed ([#52](https://github.com/Marchland/bastion/issues/52)) ([9c5326d](https://github.com/Marchland/bastion/commit/9c5326d4b55bddaa9a00c0d862dd2154ed3ef8d1))
* add tertiary type support ([#41](https://github.com/Marchland/bastion/issues/41)) ([2c35652](https://github.com/Marchland/bastion/commit/2c35652c826f6c3607aa26a04702283664c27590))
* add webmention http and discovery helpers ([d5ac278](https://github.com/Marchland/bastion/commit/d5ac27898510ed4bcbbc9852d48d64d504983b01))
* add webmention http client ([b8697a2](https://github.com/Marchland/bastion/commit/b8697a2cfd9cab4d2ca4f8c5d10f688ba552b8d1))
* add webmention receive controller with request verification ([350ed06](https://github.com/Marchland/bastion/commit/350ed06c62d4ab93bed1570d2be08039a754ac9b))
* add webmention settings ([eb2d26c](https://github.com/Marchland/bastion/commit/eb2d26c1f885aee2a193479eadb260f715a00d66))
* **api:** expose post syndications on single post lookup ([#61](https://github.com/Marchland/bastion/issues/61)) ([f5fcc75](https://github.com/Marchland/bastion/commit/f5fcc75379ecb9f53dc35f4cd787d7e1705dc39f))
* **api:** replace graphql with rest post feed and lookup ([#38](https://github.com/Marchland/bastion/issues/38)) ([e1f39e4](https://github.com/Marchland/bastion/commit/e1f39e4a1de4cf05ae9f49eec8cbdc765c52fd6e))
* **api:** require non-null for generated post and webmention fields ([#46](https://github.com/Marchland/bastion/issues/46)) ([5b9b5b4](https://github.com/Marchland/bastion/commit/5b9b5b452a23148195b77998cb28412094a60832))
* **api:** return all content entries as arrays in PostResponse ([#42](https://github.com/Marchland/bastion/issues/42)) ([e8dbbfe](https://github.com/Marchland/bastion/commit/e8dbbfe372a0fb2ca4df7c2e6b354e214b9941fb))
* block self-webmentions and syndicate excerpts with permalink ([#57](https://github.com/Marchland/bastion/issues/57)) ([d9c43b2](https://github.com/Marchland/bastion/commit/d9c43b2f575558c80e603d0ca50ec95e549140b6))
* classify rsvp webmentions ([9f1c183](https://github.com/Marchland/bastion/commit/9f1c183d65121301c952f3f1c5418a92d630aaa9))
* **content:** relay the outbox on commit and dedupe events per id ([#85](https://github.com/Marchland/bastion/issues/85)) ([24daae5](https://github.com/Marchland/bastion/commit/24daae53d2c68b0298a1867e59712fb070aef412))
* **cors:** share public wildcard CORS source between micropub and indieauth ([#30](https://github.com/Marchland/bastion/issues/30)) ([83d386c](https://github.com/Marchland/bastion/commit/83d386c6958ac830dd5f43ee0652510e474b4b47))
* detect webmention interaction types from source documents ([e32733d](https://github.com/Marchland/bastion/commit/e32733dd334b79b69610debc204f234b01448e03))
* dispatch webmentions on post lifecycle ([7037a16](https://github.com/Marchland/bastion/commit/7037a1616d8c3e1158130cbf8fccfc63d5d34c83))
* enable lowercase post-type in feed query ([0a4a722](https://github.com/Marchland/bastion/commit/0a4a722daa4f1b6be400713740a1e2e4b6e37a33))
* expose published posts over a public graphql api ([7e9702b](https://github.com/Marchland/bastion/commit/7e9702b61c4389d748f07dbd716f12d54fdf2d20))
* expose webmentions and counts over graphql ([2255e76](https://github.com/Marchland/bastion/commit/2255e76587b0329c942d558f836aff058c9d968b))
* **graphql:** add year/month/day filtering to posts feed ([#34](https://github.com/Marchland/bastion/issues/34)) ([a6f6ee5](https://github.com/Marchland/bastion/commit/a6f6ee5cf1a2eaee3ba6196a2b6bb20c239f4065))
* **graphql:** type Post.properties as mf2 union ([#36](https://github.com/Marchland/bastion/issues/36)) ([9e39fe0](https://github.com/Marchland/bastion/commit/9e39fe0524a6c4bfacc297cfe38635c23e5de0fa))
* **indieauth:** allow any origin for token and discovery via dedicated CORS chain ([#28](https://github.com/Marchland/bastion/issues/28)) ([7d22610](https://github.com/Marchland/bastion/commit/7d2261040c2b8630884cd43ffe0a9978ac422ede))
* **indieauth:** allow empty-scope login-only flows ([#63](https://github.com/Marchland/bastion/issues/63)) ([60f962d](https://github.com/Marchland/bastion/commit/60f962d09979e8a8ca6a4c9bae2554c3ebf3fbf0))
* **indieauth:** expose providers for Herald auth page ([#26](https://github.com/Marchland/bastion/issues/26)) ([f503649](https://github.com/Marchland/bastion/commit/f503649c29a5b8ce80a19a0732dd9f3c132fe30d))
* **indieauth:** IndieAuth server metadata and spec audit fixes ([#67](https://github.com/Marchland/bastion/issues/67)) ([d848cd8](https://github.com/Marchland/bastion/commit/d848cd8ad860eb92b349586caf6c6949e8b19c13))
* **indieauth:** make Bastion its own IndieAuth provider ([#16](https://github.com/Marchland/bastion/issues/16)) ([4509197](https://github.com/Marchland/bastion/commit/4509197a18aed92f35c06cfc45afb98092f096ee))
* initial commit with working micropub ([ae2d6b8](https://github.com/Marchland/bastion/commit/ae2d6b895b5632f9463e57b78bfb08273afa2889))
* make JetStream stream replicas configurable ([#80](https://github.com/Marchland/bastion/issues/80)) ([0c3b8b1](https://github.com/Marchland/bastion/commit/0c3b8b122ba7bed51b37b54637addbf1867a53e1))
* **micropub:** syndicate posts to downstream micropub targets ([#18](https://github.com/Marchland/bastion/issues/18)) ([27e0d43](https://github.com/Marchland/bastion/commit/27e0d43f205b5e7cba8dfcbeac6b941011712fdc))
* return a gone result for deleted posts in graphql ([b975db6](https://github.com/Marchland/bastion/commit/b975db63e5d0bdb73ebdb21f6fb13434708626f6))
* send and retry webmentions ([6a25989](https://github.com/Marchland/bastion/commit/6a2598973d2a7a4e774ea284e143bfd6b0aa64ab))
* support adding and removing syndication targets via update ([#59](https://github.com/Marchland/bastion/issues/59)) ([d53d415](https://github.com/Marchland/bastion/commit/d53d41504cc2e53cd6a733c335ed6a273ff8b908))
* support profile scope for quill ([3635c62](https://github.com/Marchland/bastion/commit/3635c62efc7dc76ff0094576ce8ede1d5d1bd526))
* track webmention notifications ([e7ffb5e](https://github.com/Marchland/bastion/commit/e7ffb5ec188c0d6df4ba6419e05200b17cb208d1))
* unify tertiary types into post type discovery and serve post-types from external config ([#70](https://github.com/Marchland/bastion/issues/70)) ([006bfad](https://github.com/Marchland/bastion/commit/006bfadfc224553e59075499b129e2c181999277))
* untagged post lookup ([#54](https://github.com/Marchland/bastion/issues/54)) ([c057733](https://github.com/Marchland/bastion/commit/c057733b17603021f9917c30634ad4ac3f5091c8))
* verify received webmention sources asynchronously ([4553aaa](https://github.com/Marchland/bastion/commit/4553aaabfb0389dbceefef0a4d46849347992117))
* **webmention:** return all content entries as arrays ([#44](https://github.com/Marchland/bastion/issues/44)) ([fda5ea1](https://github.com/Marchland/bastion/commit/fda5ea13cf7ebfb9b2d5dfa3b13864774b46f549))
* **websub:** publish posts over WebSub (PubSubHubbub) ([e853b94](https://github.com/Marchland/bastion/commit/e853b94e6b7c9261bb8262d6ed20e3ed889bb747))
* **websub:** publish posts over WebSub (PubSubHubbub) ([0bfe457](https://github.com/Marchland/bastion/commit/0bfe45719c5ca0529d34055e5b84ff3113c4173c))


### Bug Fixes

* allow graphql schema introspection ([e08834e](https://github.com/Marchland/bastion/commit/e08834ed64b7090a2907d5d8abc0ce71c05c92d3))
* base webmention undelete dispatch on public content not deletion ([7934ffa](https://github.com/Marchland/bastion/commit/7934ffa901258ba3d11fa66404046b6f23a3cabc))
* cache discovery only when advertised, rediscover on updates, and add cleanup job ([66972db](https://github.com/Marchland/bastion/commit/66972db66c727bc974dc3aa60f46da646d308d2f))
* classify post types per the post-type discovery algorithm ([b4f2eb9](https://github.com/Marchland/bastion/commit/b4f2eb93ff65a688af5438325ddc47cb5c97e7a6))
* **db:** revert formatting of SQL migrations to restore Flyway checksums ([#24](https://github.com/Marchland/bastion/issues/24)) ([a356c4d](https://github.com/Marchland/bastion/commit/a356c4d2631f2508c9002af670b0666a327e5ffd))
* fix url service test to accept variable schemes ([7cc35a2](https://github.com/Marchland/bastion/commit/7cc35a24dc469a660b6106f308141565ab265d7e))
* ignore unknown scopes when deserializing micropub tokens ([2e92973](https://github.com/Marchland/bastion/commit/2e929736cb2112329817723e31da4321ef8f977e))
* implement the current graphql coercing api in the json scalar ([423aeac](https://github.com/Marchland/bastion/commit/423aeacf37d1f75d77825f5eb5101f468268a16c))
* include indieauth response body in token validation logs ([59ac2b9](https://github.com/Marchland/bastion/commit/59ac2b9b0f1b61a53defea4ccdf0be01d83f39dc))
* include receiver response body in webmention failures ([d30b18e](https://github.com/Marchland/bastion/commit/d30b18e01e2761b63df60f6ca677e2370da003ca))
* **indieauth:** accept POST code exchange at authorization endpoint ([#65](https://github.com/Marchland/bastion/issues/65)) ([42c13ef](https://github.com/Marchland/bastion/commit/42c13ef22446217baa45d7e9e31dffd245952bc1))
* **indieauth:** require PKCE, harden error handling, purge dead auth rows ([#17](https://github.com/Marchland/bastion/issues/17)) ([e44188b](https://github.com/Marchland/bastion/commit/e44188ba4a969c26a23c2b9ccbd41034f65a074f))
* make publiclyReachable account for deleted posts ([96cce36](https://github.com/Marchland/bastion/commit/96cce36cf8dd7620eb8eb6a03e4a9110f6f20ed0))
* make schema lenient on URL checking to allow for http/https mixing ([44c45da](https://github.com/Marchland/bastion/commit/44c45daae2d2e25f2edb193ca5abce6e610f49d5))
* **micropub:** treat blank command values as absent ([#32](https://github.com/Marchland/bastion/issues/32)) ([5d4fb7d](https://github.com/Marchland/bastion/commit/5d4fb7d6a12420f00460f49836397b42483f86d0))
* persist mf2 objects through hibernate json ([5fc8f1e](https://github.com/Marchland/bastion/commit/5fc8f1e4f87660785bdc1d7e04eac21597c98771))
* reject micropub tokens issued for other identities ([7274f08](https://github.com/Marchland/bastion/commit/7274f08098325fc56429dc5328e0e5588c36920b))
* remove service doc from auth metadata, as indielib has a minor bug, and it's not important for bastion anyway ([747edd0](https://github.com/Marchland/bastion/commit/747edd00224a1b1201766d6e20ec4fb8087b7d3d))
* scope the micropub auth filter to the micropub security chain ([77ae463](https://github.com/Marchland/bastion/commit/77ae46371ed4efad3df26177939fd8e92d5ca8d4))
* skip webmention rel elements without an href during discovery ([1b7e53a](https://github.com/Marchland/bastion/commit/1b7e53a11f44d89a05eef700fcd823de3601a61a))
* update posts through the managed entity without touching generated columns ([00a42c8](https://github.com/Marchland/bastion/commit/00a42c8b26ace343181ca0aece2f4dfb27c5e6b9))


### Reverts

* restore the Bastion monolith ([#87](https://github.com/Marchland/bastion/issues/87)) ([1fad6c5](https://github.com/Marchland/bastion/commit/1fad6c57523e81e552f1a921f15f51636e6683df))


### Miscellaneous Chores

* add local flyway config to clean the db every time it starts in local mode ([86d2b6d](https://github.com/Marchland/bastion/commit/86d2b6d61ba8ac17460bba7e726bc59bd976196a))
* cleanup ([1dd4ae5](https://github.com/Marchland/bastion/commit/1dd4ae5c24d759e78cbd6eb71af3c0bb496a381a))
* externalize micropub syndication target config and clean up prod properties ([6c146db](https://github.com/Marchland/bastion/commit/6c146db62bf9a36cf5e456dd5b4e5658f0a83bbb))
* fix formatting according to ktlint ([df5e0c2](https://github.com/Marchland/bastion/commit/df5e0c285324b352de9ad13d64212622e8bef572))
* **main:** release 1.0.0 ([#1](https://github.com/Marchland/bastion/issues/1)) ([f9f8bf3](https://github.com/Marchland/bastion/commit/f9f8bf38d3b4ceb5494779b1de28df8b2fa64e9a))
* **main:** release 1.0.1 ([#2](https://github.com/Marchland/bastion/issues/2)) ([15b2498](https://github.com/Marchland/bastion/commit/15b2498e125a7853e5c32b6e581d7143e817d8f4))
* **main:** release 1.1.0 ([#3](https://github.com/Marchland/bastion/issues/3)) ([5afaf46](https://github.com/Marchland/bastion/commit/5afaf46217414f5c52fcd72a0792d675c62d58b3))
* **main:** release 1.2.0 ([#4](https://github.com/Marchland/bastion/issues/4)) ([6730a9e](https://github.com/Marchland/bastion/commit/6730a9ed4bdefeb316d00b61063cdd734a1b7c80))
* **main:** release 1.2.1 ([#5](https://github.com/Marchland/bastion/issues/5)) ([1bb5a3c](https://github.com/Marchland/bastion/commit/1bb5a3cc5524c4587a86dc05c852d89e35367adc))
* **main:** release 1.2.2 ([#6](https://github.com/Marchland/bastion/issues/6)) ([6e3c535](https://github.com/Marchland/bastion/commit/6e3c53547f8d198fe34ffa0240ba23e1f4c29ff9))
* **main:** release 1.2.3 ([#7](https://github.com/Marchland/bastion/issues/7)) ([02b52ec](https://github.com/Marchland/bastion/commit/02b52eca60770f07dc4803bea0b3160ef2358c82))
* **main:** release 1.2.4 ([#8](https://github.com/Marchland/bastion/issues/8)) ([b464405](https://github.com/Marchland/bastion/commit/b46440561ddb5807a052d0a460465dc7b1787ed0))
* **main:** release 1.3.0 ([#9](https://github.com/Marchland/bastion/issues/9)) ([4fe2fc3](https://github.com/Marchland/bastion/commit/4fe2fc337047e2f2c80360a4ad28c447d245b202))
* **main:** release 1.3.1 ([#10](https://github.com/Marchland/bastion/issues/10)) ([cdf2286](https://github.com/Marchland/bastion/commit/cdf22864d497dabb1d668ab335f17373836b165e))
* **main:** release 1.3.10 ([#31](https://github.com/Marchland/bastion/issues/31)) ([f28b18a](https://github.com/Marchland/bastion/commit/f28b18a59a416f953dc78a4e872459e44733f00d))
* **main:** release 1.3.11 ([#33](https://github.com/Marchland/bastion/issues/33)) ([1faab71](https://github.com/Marchland/bastion/commit/1faab715fc56d0c551360e77820dae05edfe05c4))
* **main:** release 1.3.12 ([#35](https://github.com/Marchland/bastion/issues/35)) ([a6b3757](https://github.com/Marchland/bastion/commit/a6b3757af7962bd879d9da5e3cc2a734ac5ae75f))
* **main:** release 1.3.13 ([#37](https://github.com/Marchland/bastion/issues/37)) ([9982cff](https://github.com/Marchland/bastion/commit/9982cff0240ca87c0aaad6be702619504ebc6765))
* **main:** release 1.3.14 ([#39](https://github.com/Marchland/bastion/issues/39)) ([38ee202](https://github.com/Marchland/bastion/commit/38ee202c0e31f9c08fc82573b398e913618432ab))
* **main:** release 1.3.15 ([#40](https://github.com/Marchland/bastion/issues/40)) ([8797b9b](https://github.com/Marchland/bastion/commit/8797b9b9bbaa2290e5981598492e9451b23ee76d))
* **main:** release 1.3.16 ([#43](https://github.com/Marchland/bastion/issues/43)) ([66a1472](https://github.com/Marchland/bastion/commit/66a1472a3f4ba4f7625282d9c540187f7d1dca8c))
* **main:** release 1.3.17 ([#45](https://github.com/Marchland/bastion/issues/45)) ([b7a090c](https://github.com/Marchland/bastion/commit/b7a090cf645ee746eb2b3bb4ccb561eba474dbc6))
* **main:** release 1.3.18 ([#47](https://github.com/Marchland/bastion/issues/47)) ([82f5144](https://github.com/Marchland/bastion/commit/82f51445b94c2a8e134ac79fdeb194f8765141b5))
* **main:** release 1.3.19 ([#49](https://github.com/Marchland/bastion/issues/49)) ([4a833db](https://github.com/Marchland/bastion/commit/4a833dbea516176c469248c81f27d01791aefe69))
* **main:** release 1.3.2 ([6d8857a](https://github.com/Marchland/bastion/commit/6d8857a4de6918391bad4c5cc2bd843976d3e05c))
* **main:** release 1.3.2 ([4de21eb](https://github.com/Marchland/bastion/commit/4de21eb9015dde00d094d198e58302eaf151b18d))
* **main:** release 1.3.20 ([#50](https://github.com/Marchland/bastion/issues/50)) ([26b0b4e](https://github.com/Marchland/bastion/commit/26b0b4ec9b3ed00ed8bbafde88c7db934c877d46))
* **main:** release 1.3.21 ([#51](https://github.com/Marchland/bastion/issues/51)) ([47c5788](https://github.com/Marchland/bastion/commit/47c57887ee12858077e55554ac584e6fd8fc2ace))
* **main:** release 1.3.22 ([#53](https://github.com/Marchland/bastion/issues/53)) ([16ccb82](https://github.com/Marchland/bastion/commit/16ccb8297669a240ef779a7a58b28a7d686b8e54))
* **main:** release 1.3.23 ([#55](https://github.com/Marchland/bastion/issues/55)) ([2575372](https://github.com/Marchland/bastion/commit/25753727e188e30a72750ce32d3823c9d9d2a6a0))
* **main:** release 1.3.24 ([#56](https://github.com/Marchland/bastion/issues/56)) ([3ed6840](https://github.com/Marchland/bastion/commit/3ed684098ebd49c38e62a1163ebca01b52231b2f))
* **main:** release 1.3.25 ([#58](https://github.com/Marchland/bastion/issues/58)) ([a2740bb](https://github.com/Marchland/bastion/commit/a2740bb5f479eae71934f070c40d32689018ad04))
* **main:** release 1.3.26 ([#60](https://github.com/Marchland/bastion/issues/60)) ([1cb7842](https://github.com/Marchland/bastion/commit/1cb784285c1a0c8e532a969b64061ca11e0b4336))
* **main:** release 1.3.27 ([#62](https://github.com/Marchland/bastion/issues/62)) ([d383315](https://github.com/Marchland/bastion/commit/d3833154cc543874ac72e69bef81d0bcac6bc84a))
* **main:** release 1.3.28 ([#64](https://github.com/Marchland/bastion/issues/64)) ([9290961](https://github.com/Marchland/bastion/commit/9290961190b27f95f4f49f35e3e43810fd8b5d35))
* **main:** release 1.3.29 ([#66](https://github.com/Marchland/bastion/issues/66)) ([f555429](https://github.com/Marchland/bastion/commit/f555429a0c726eb8fe3ec4327b4aa02c41f909df))
* **main:** release 1.3.3 ([#15](https://github.com/Marchland/bastion/issues/15)) ([1917255](https://github.com/Marchland/bastion/commit/191725507c334d6d779733d7b5f9896acdd05d2a))
* **main:** release 1.3.30 ([#68](https://github.com/Marchland/bastion/issues/68)) ([b07e700](https://github.com/Marchland/bastion/commit/b07e7008ba909a12d7d9409613844047bcca014c))
* **main:** release 1.3.31 ([#69](https://github.com/Marchland/bastion/issues/69)) ([cbff384](https://github.com/Marchland/bastion/commit/cbff384f178cf9b2bfbcf5a35f74b951d046ec66))
* **main:** release 1.3.32 ([#71](https://github.com/Marchland/bastion/issues/71)) ([d88f30f](https://github.com/Marchland/bastion/commit/d88f30f69441b6f7f4eea3ba63f3ca62519c3cbe))
* **main:** release 1.3.33 ([#73](https://github.com/Marchland/bastion/issues/73)) ([279d9be](https://github.com/Marchland/bastion/commit/279d9be1859fbcaceda8902bdddb6a2938731582))
* **main:** release 1.3.4 ([#20](https://github.com/Marchland/bastion/issues/20)) ([e20c009](https://github.com/Marchland/bastion/commit/e20c0096a5577b299b2da1d792ac5c072f19aebf))
* **main:** release 1.3.5 ([#22](https://github.com/Marchland/bastion/issues/22)) ([aaba63e](https://github.com/Marchland/bastion/commit/aaba63ebd1c4b80dfbd0dc8ce49cb7dfa9d6a760))
* **main:** release 1.3.6 ([#23](https://github.com/Marchland/bastion/issues/23)) ([bf42f3c](https://github.com/Marchland/bastion/commit/bf42f3c34520d5b22d0bb08a264a35033eb9af03))
* **main:** release 1.3.7 ([#25](https://github.com/Marchland/bastion/issues/25)) ([f8798a2](https://github.com/Marchland/bastion/commit/f8798a2441c005296cf2eaeb665cc056d677608f))
* **main:** release 1.3.8 ([#27](https://github.com/Marchland/bastion/issues/27)) ([5cb7ddd](https://github.com/Marchland/bastion/commit/5cb7ddda8c2e97565522f7d454fe154f2bc711ec))
* **main:** release 1.3.9 ([#29](https://github.com/Marchland/bastion/issues/29)) ([6b6da31](https://github.com/Marchland/bastion/commit/6b6da3112c9b55253d8f1a51ce9302b32d76b598))
* **main:** release 2.0.0 ([#78](https://github.com/Marchland/bastion/issues/78)) ([67f8228](https://github.com/Marchland/bastion/commit/67f8228efb66e1cd9a93aff265518190d9ae0315))
* **main:** release 2.0.1 ([#82](https://github.com/Marchland/bastion/issues/82)) ([5c84e89](https://github.com/Marchland/bastion/commit/5c84e897a8f872e8f972f939d51fbd4a151fa4bb))
* **main:** release 2.0.2 ([#84](https://github.com/Marchland/bastion/issues/84)) ([5ecab7a](https://github.com/Marchland/bastion/commit/5ecab7a1353d438e42f8d736a490b7f17093de2f))
* **main:** release 2.1.0 ([#86](https://github.com/Marchland/bastion/issues/86)) ([f7a62fa](https://github.com/Marchland/bastion/commit/f7a62fa3387991da2f2cd2a038303a107e355749))
* move packages to Marchland and rename mf24j -&gt; microformats2 ([#81](https://github.com/Marchland/bastion/issues/81)) ([7e8c473](https://github.com/Marchland/bastion/commit/7e8c473dc56caca58021ffed2ff35ca6ed5c3569))
* unnecessary non-null assertion ([5794fd7](https://github.com/Marchland/bastion/commit/5794fd7bd43976d4b9a1431d094d1e2876565ecc))


### Code Refactoring

* Bastion content authority, projections, and cleanup ([#75](https://github.com/Marchland/bastion/issues/75), [#77](https://github.com/Marchland/bastion/issues/77)) ([6837bef](https://github.com/Marchland/bastion/commit/6837befeb5792f691a2448dd648b128d9231139a))
* deslop the webmention, micropub and mf2 layers ([#11](https://github.com/Marchland/bastion/issues/11)) ([98d2b6a](https://github.com/Marchland/bastion/commit/98d2b6a32598b3bb02d8700cbff766b8599c9cd2))
* model the mf2 parser as an interface with an implementation ([6b49ebe](https://github.com/Marchland/bastion/commit/6b49ebe92a0e3980655fc880acbe0706d82fc7f6))
* move mf2 and url types to shared packages ([81dfe69](https://github.com/Marchland/bastion/commit/81dfe690c5cc7a4b0bba90faf07de169096f6b7d))
* move the public read API under content/ ([#79](https://github.com/Marchland/bastion/issues/79)) ([aacb5d4](https://github.com/Marchland/bastion/commit/aacb5d424aa135f8258873f9c803b031d9c1df26))
* relocate the post type enum to the micropub type package ([cfeaeec](https://github.com/Marchland/bastion/commit/cfeaeec33e9aee4a20c3458fe5e29c01f2da295f))


### Tests

* authenticate seeding of graphql post integration tests ([6c3d0fb](https://github.com/Marchland/bastion/commit/6c3d0fbac2827d03ee6f8e0453cf793130b11782))
* cover deleted post lookup over graphql ([786582c](https://github.com/Marchland/bastion/commit/786582c6639599d400516a9981f5ea305287a362))
* cover graphql post queries ([7407c37](https://github.com/Marchland/bastion/commit/7407c3731983359df44f592530b65ee1a871e330))
* cover graphql webmention queries and rsvp classification ([8874040](https://github.com/Marchland/bastion/commit/88740401739e2a361e4e21c4f7720c250b9ae290))
* fix graphql list fixtures and assertions ([1d7cd2f](https://github.com/Marchland/bastion/commit/1d7cd2f789a86a9605b833430320fc1798e5052b))
* isolate graphql integration posts between tests ([937c2b9](https://github.com/Marchland/bastion/commit/937c2b91fd82cf902505238206829523cdc854a9))


### Build System

* add release-please for automatic version bumps ([f3e69c9](https://github.com/Marchland/bastion/commit/f3e69c92527ff4b894cf93076dda689c6b68726e))
* bump microformats2 to 0.1.2 ([#83](https://github.com/Marchland/bastion/issues/83)) ([25392b3](https://github.com/Marchland/bastion/commit/25392b38071835948c02cac562c985bd076d9a0a))
* drop graalvm native in favour of jvm packaging ([55b7e2c](https://github.com/Marchland/bastion/commit/55b7e2c2e3c828cd14f77f13ab892c2e35a30981))
* raise native-image builder heap for graalvm image builds ([1afda23](https://github.com/Marchland/bastion/commit/1afda23e7490dbd2bed31f99d4acad5048aecc91))
* register native-image resource hints for flyway migrations ([8cd0769](https://github.com/Marchland/bastion/commit/8cd0769dc2ba67f5d17d18ddff2307cfa7d4bf8b))


### Continuous Integration

* add workflow and distroless image for graalvm native build ([0ba08ae](https://github.com/Marchland/bastion/commit/0ba08ae3cc89da69054d3403c95560c37a3cd9d6))
* build a jvm distroless image ([3f02d7a](https://github.com/Marchland/bastion/commit/3f02d7a488f0a8644fed00169268a8a3b9400be8))
* bump patch releases on every merge and surface all commit types in the changelog ([c780225](https://github.com/Marchland/bastion/commit/c780225a35af6e4731925d323d81cf43f0778588))
* gate builds on the full integration test suite ([94ee5d3](https://github.com/Marchland/bastion/commit/94ee5d31ec74902280b26d07aad9062697ca829c))
* grant release-please a token that can open pull requests ([982d3f7](https://github.com/Marchland/bastion/commit/982d3f756154fbf048ff3dc4241546f73def6939))
* pass repository to release image dispatch ([8519544](https://github.com/Marchland/bastion/commit/85195442867f6adac65059183ff1f4cb4e51e02e))
* publish semver images from releases using only github token ([999301d](https://github.com/Marchland/bastion/commit/999301d556d2e7be9bcf45074bac1644ffac2445))
* set the expected me env for token validation ([bee9d18](https://github.com/Marchland/bastion/commit/bee9d18395f6e75ad24f63acab1b7e0f3680f5d5))
* use a container buildx builder for image cache export ([0abdc68](https://github.com/Marchland/bastion/commit/0abdc68b8d955ec061a0230073676eee0f3f141c))
* use default semver versioning for release-please ([f273224](https://github.com/Marchland/bastion/commit/f2732240905ee1fe38ec0f34877f86e4ee5f3e15))
* use setup-java v6 with oracle graalvm for the native build ([6d512ea](https://github.com/Marchland/bastion/commit/6d512ea1d17929dc4304d579a237cec8af0d9b46))

## [1.3.33](https://github.com/jacobsandersen/bastion/compare/v1.3.32...v1.3.33) (2026-10-05)


### ⚠ BREAKING CHANGES

* add q=properties micropub query ([#72](https://github.com/jacobsandersen/bastion/issues/72))

### Features

* add q=properties micropub query ([#72](https://github.com/jacobsandersen/bastion/issues/72)) ([6bd9d43](https://github.com/jacobsandersen/bastion/commit/6bd9d435f176e8b8d65343d2ce05840b6c678986))

## [1.3.32](https://github.com/jacobsandersen/bastion/compare/v1.3.31...v1.3.32) (2026-10-05)


### Features

* unify tertiary types into post type discovery and serve post-types from external config ([#70](https://github.com/jacobsandersen/bastion/issues/70)) ([3561027](https://github.com/jacobsandersen/bastion/commit/3561027d60b41d40bc965d3dfd08c102b3a09c74))

## [1.3.31](https://github.com/jacobsandersen/bastion/compare/v1.3.30...v1.3.31) (2026-10-04)


### Bug Fixes

* remove service doc from auth metadata, as indielib has a minor bug, and it's not important for bastion anyway ([eb02a3d](https://github.com/jacobsandersen/bastion/commit/eb02a3d4dcc5959aff91fb5711db25da28faeab0))

## [1.3.30](https://github.com/jacobsandersen/bastion/compare/v1.3.29...v1.3.30) (2026-10-03)


### Features

* **indieauth:** IndieAuth server metadata and spec audit fixes ([#67](https://github.com/jacobsandersen/bastion/issues/67)) ([b8aa32f](https://github.com/jacobsandersen/bastion/commit/b8aa32f128abcac20fc9ba72378786d79461eba4))

## [1.3.29](https://github.com/jacobsandersen/bastion/compare/v1.3.28...v1.3.29) (2026-10-02)


### Bug Fixes

* **indieauth:** accept POST code exchange at authorization endpoint ([#65](https://github.com/jacobsandersen/bastion/issues/65)) ([c511dd8](https://github.com/jacobsandersen/bastion/commit/c511dd8e942adf00be5c7c7fc8fc935dda2e7538))

## [1.3.28](https://github.com/jacobsandersen/bastion/compare/v1.3.27...v1.3.28) (2026-10-02)


### Features

* **indieauth:** allow empty-scope login-only flows ([#63](https://github.com/jacobsandersen/bastion/issues/63)) ([a0b7640](https://github.com/jacobsandersen/bastion/commit/a0b764068144af34229cff9dca57790e2acfbeb4))

## [1.3.27](https://github.com/jacobsandersen/bastion/compare/v1.3.26...v1.3.27) (2026-10-02)


### Features

* **api:** expose post syndications on single post lookup ([#61](https://github.com/jacobsandersen/bastion/issues/61)) ([d83c41c](https://github.com/jacobsandersen/bastion/commit/d83c41cca969a0070d9683c3a8fe1340177a6784))

## [1.3.26](https://github.com/jacobsandersen/bastion/compare/v1.3.25...v1.3.26) (2026-10-02)


### Features

* support adding and removing syndication targets via update ([#59](https://github.com/jacobsandersen/bastion/issues/59)) ([7d6513a](https://github.com/jacobsandersen/bastion/commit/7d6513a68420e4c52d7f63fbe929cae7d57685f5))

## [1.3.25](https://github.com/jacobsandersen/bastion/compare/v1.3.24...v1.3.25) (2026-10-02)


### Features

* block self-webmentions and syndicate excerpts with permalink ([#57](https://github.com/jacobsandersen/bastion/issues/57)) ([c893336](https://github.com/jacobsandersen/bastion/commit/c8933363c709a9fa60d6b20cd66e3a2c30376db3))

## [1.3.24](https://github.com/jacobsandersen/bastion/compare/v1.3.23...v1.3.24) (2026-10-01)


### Features

* support profile scope for quill ([190c7dc](https://github.com/jacobsandersen/bastion/commit/190c7dcee183bf049c016a7c300dd304c524e125))

## [1.3.23](https://github.com/jacobsandersen/bastion/compare/v1.3.22...v1.3.23) (2026-09-25)


### Features

* untagged post lookup ([#54](https://github.com/jacobsandersen/bastion/issues/54)) ([4d5b1f1](https://github.com/jacobsandersen/bastion/commit/4d5b1f1bd99c060fba7695d4dc330b2933a43702))

## [1.3.22](https://github.com/jacobsandersen/bastion/compare/v1.3.21...v1.3.22) (2026-09-25)


### Features

* add tag listing and tag-filtered post feed ([#52](https://github.com/jacobsandersen/bastion/issues/52)) ([fd11609](https://github.com/jacobsandersen/bastion/commit/fd11609ac790dbe73f0abae390c0e662be9bbd3b))

## [1.3.21](https://github.com/jacobsandersen/bastion/compare/v1.3.20...v1.3.21) (2026-09-25)


### Bug Fixes

* fix url service test to accept variable schemes ([6d0912e](https://github.com/jacobsandersen/bastion/commit/6d0912e7bdd3ce53768de667d6773034f6ef0557))

## [1.3.20](https://github.com/jacobsandersen/bastion/compare/v1.3.19...v1.3.20) (2026-09-25)


### Bug Fixes

* make schema lenient on URL checking to allow for http/https mixing ([f8b0b82](https://github.com/jacobsandersen/bastion/commit/f8b0b82c2f6fad0ce3ddbfdd98b9c6676a671ef4))

## [1.3.19](https://github.com/jacobsandersen/bastion/compare/v1.3.18...v1.3.19) (2026-09-24)


### Features

* add more filters for feed lookup, tighten post lookup, improve post response ([c1e6d45](https://github.com/jacobsandersen/bastion/commit/c1e6d45a76ae3547026fedf5254eeb4a3117db81))

## [1.3.18](https://github.com/jacobsandersen/bastion/compare/v1.3.17...v1.3.18) (2026-09-23)


### Features

* **api:** require non-null for generated post and webmention fields ([#46](https://github.com/jacobsandersen/bastion/issues/46)) ([1155ad1](https://github.com/jacobsandersen/bastion/commit/1155ad1dd5d1dfbf0eb2656be91fd1094109de0d))

## [1.3.17](https://github.com/jacobsandersen/bastion/compare/v1.3.16...v1.3.17) (2026-09-23)


### Features

* **webmention:** return all content entries as arrays ([#44](https://github.com/jacobsandersen/bastion/issues/44)) ([6a55b49](https://github.com/jacobsandersen/bastion/commit/6a55b49c58dab1ef2666d782c3871fe677c40a42))

## [1.3.16](https://github.com/jacobsandersen/bastion/compare/v1.3.15...v1.3.16) (2026-09-23)


### Features

* **api:** return all content entries as arrays in PostResponse ([#42](https://github.com/jacobsandersen/bastion/issues/42)) ([d5de37e](https://github.com/jacobsandersen/bastion/commit/d5de37e67a58a6106aac34385de514f76ca8c9c2))

## [1.3.15](https://github.com/jacobsandersen/bastion/compare/v1.3.14...v1.3.15) (2026-09-23)


### Features

* add tertiary type support ([#41](https://github.com/jacobsandersen/bastion/issues/41)) ([38cec13](https://github.com/jacobsandersen/bastion/commit/38cec1394e253717ade74cf33d3acb25850de337))
* enable lowercase post-type in feed query ([958f15e](https://github.com/jacobsandersen/bastion/commit/958f15e3f5a5efbdd8ac28a6d9b44b299584f23b))

## [1.3.14](https://github.com/jacobsandersen/bastion/compare/v1.3.13...v1.3.14) (2026-09-23)


### Features

* **api:** replace graphql with rest post feed and lookup ([#38](https://github.com/jacobsandersen/bastion/issues/38)) ([9fbb243](https://github.com/jacobsandersen/bastion/commit/9fbb243db5e2b596d7a6aeaaa0c822666cdfa97d))

## [1.3.13](https://github.com/jacobsandersen/bastion/compare/v1.3.12...v1.3.13) (2026-09-22)


### Features

* **graphql:** type Post.properties as mf2 union ([#36](https://github.com/jacobsandersen/bastion/issues/36)) ([dec9bd7](https://github.com/jacobsandersen/bastion/commit/dec9bd75f0da1a3cbbba13e9fa828de0f77721f2))

## [1.3.12](https://github.com/jacobsandersen/bastion/compare/v1.3.11...v1.3.12) (2026-09-22)


### Features

* **graphql:** add year/month/day filtering to posts feed ([#34](https://github.com/jacobsandersen/bastion/issues/34)) ([a6fe08e](https://github.com/jacobsandersen/bastion/commit/a6fe08ef2c94a07682b32ae8a20c51612ebf101c))

## [1.3.11](https://github.com/jacobsandersen/bastion/compare/v1.3.10...v1.3.11) (2026-09-21)


### Bug Fixes

* **micropub:** treat blank command values as absent ([#32](https://github.com/jacobsandersen/bastion/issues/32)) ([482d5ca](https://github.com/jacobsandersen/bastion/commit/482d5caf9fb9f82768d50d281895caaf15dbf055))

## [1.3.10](https://github.com/jacobsandersen/bastion/compare/v1.3.9...v1.3.10) (2026-09-21)


### Features

* **cors:** share public wildcard CORS source between micropub and indieauth ([#30](https://github.com/jacobsandersen/bastion/issues/30)) ([8b54c7a](https://github.com/jacobsandersen/bastion/commit/8b54c7a2d102f4d83f4dab2ec38e6067421cbf02))

## [1.3.9](https://github.com/jacobsandersen/bastion/compare/v1.3.8...v1.3.9) (2026-09-21)


### Features

* **indieauth:** allow any origin for token and discovery via dedicated CORS chain ([#28](https://github.com/jacobsandersen/bastion/issues/28)) ([c806fa5](https://github.com/jacobsandersen/bastion/commit/c806fa5573aedf0f712f1ae9d222397c58c76c62))

## [1.3.8](https://github.com/jacobsandersen/bastion/compare/v1.3.7...v1.3.8) (2026-09-21)


### Features

* **indieauth:** expose providers for Herald auth page ([#26](https://github.com/jacobsandersen/bastion/issues/26)) ([2c83a93](https://github.com/jacobsandersen/bastion/commit/2c83a939f5c68c60dc6baafe4776b1fdbd4b8145))

## [1.3.7](https://github.com/jacobsandersen/bastion/compare/v1.3.6...v1.3.7) (2026-09-20)


### Bug Fixes

* **db:** revert formatting of SQL migrations to restore Flyway checksums ([#24](https://github.com/jacobsandersen/bastion/issues/24)) ([115722d](https://github.com/jacobsandersen/bastion/commit/115722dccdd34a7fbd18e3724c9d7d0d4dc69e8d))

## [1.3.6](https://github.com/jacobsandersen/bastion/compare/v1.3.5...v1.3.6) (2026-09-20)


### Miscellaneous Chores

* fix formatting according to ktlint ([2e60b23](https://github.com/jacobsandersen/bastion/commit/2e60b23f2b81c4131fd1f748fe29689ce9034bbe))

## [1.3.5](https://github.com/jacobsandersen/bastion/compare/v1.3.4...v1.3.5) (2026-09-20)


### Miscellaneous Chores

* cleanup ([1b98bf7](https://github.com/jacobsandersen/bastion/commit/1b98bf7bf2d3f9628df6508971c4ac22e52f903a))

## [1.3.4](https://github.com/jacobsandersen/bastion/compare/v1.3.3...v1.3.4) (2026-09-18)

### Miscellaneous Chores

* externalize micropub syndication target config and clean up prod properties
  ([d737090](https://github.com/jacobsandersen/bastion/commit/d7370903b01ab80cd7dedfc2cb0173174e1bd63e))
* unnecessary non-null assertion
  ([ef8dfa3](https://github.com/jacobsandersen/bastion/commit/ef8dfa332afb07a97bd20fd2c41457572e1aadfc))

## [1.3.3](https://github.com/jacobsandersen/bastion/compare/v1.3.2...v1.3.3) (2026-09-09)

### Features

* **indieauth:** make Bastion its own IndieAuth provider ([#16](https://github.com/jacobsandersen/bastion/issues/16))
  ([2bc8936](https://github.com/jacobsandersen/bastion/commit/2bc89369ff90175d89846a376de16025f3f8a6bb))
* **micropub:** syndicate posts to downstream micropub targets
  ([#18](https://github.com/jacobsandersen/bastion/issues/18))
  ([c54a2da](https://github.com/jacobsandersen/bastion/commit/c54a2da93d12cfae66d6379d1eb7683ecddfc3e5))
* **websub:** publish posts over WebSub (PubSubHubbub)
  ([c48058d](https://github.com/jacobsandersen/bastion/commit/c48058d1cf6fb47914973abfd6d3edc1d747b3a5))
* **websub:** publish posts over WebSub (PubSubHubbub)
  ([2b41fa8](https://github.com/jacobsandersen/bastion/commit/2b41fa8bcf00204a10b9f02f8cc5a758a86bdb26))

### Bug Fixes

* **indieauth:** require PKCE, harden error handling, purge dead auth rows
  ([#17](https://github.com/jacobsandersen/bastion/issues/17))
  ([36d1a4a](https://github.com/jacobsandersen/bastion/commit/36d1a4a453df63c0878c9089012eb3b8a8b0dd77))

## [1.3.2](https://github.com/jacobsandersen/bastion/compare/v1.3.1...v1.3.2) (2026-09-07)

### Code Refactoring

* deslop the webmention, micropub and mf2 layers ([#11](https://github.com/jacobsandersen/bastion/issues/11))
  ([a478f24](https://github.com/jacobsandersen/bastion/commit/a478f24590d850da817bba0bcfca1b24a97a4482))

### Continuous Integration

* bump patch releases on every merge and surface all commit types in the changelog
  ([01b81f8](https://github.com/jacobsandersen/bastion/commit/01b81f898d5552aab40987cc185283e1b2aaa187))

## [1.3.1](https://github.com/jacobsandersen/bastion/compare/v1.3.0...v1.3.1) (2026-09-07)

### Bug Fixes

* update posts through the managed entity without touching generated columns
  ([5588056](https://github.com/jacobsandersen/bastion/commit/55880567bf0ca9e7994082262d91b112ac975424))

## [1.3.0](https://github.com/jacobsandersen/bastion/compare/v1.2.4...v1.3.0) (2026-09-07)

### Features

* return a gone result for deleted posts in graphql
  ([f43a589](https://github.com/jacobsandersen/bastion/commit/f43a589aebb413011bd01110ba34d6a369e652cb))

## [1.2.4](https://github.com/jacobsandersen/bastion/compare/v1.2.3...v1.2.4) (2026-09-07)

### Bug Fixes

* cache discovery only when advertised, rediscover on updates, and add cleanup job
  ([60be18e](https://github.com/jacobsandersen/bastion/commit/60be18e5b98bacfeda24f3528adaa9d2f6bc3a17))

## [1.2.3](https://github.com/jacobsandersen/bastion/compare/v1.2.2...v1.2.3) (2026-09-07)

### Bug Fixes

* skip webmention rel elements without an href during discovery
  ([237582b](https://github.com/jacobsandersen/bastion/commit/237582b0f1e9c97f50d798c8ef8dcdf56bb84609))

## [1.2.2](https://github.com/jacobsandersen/bastion/compare/v1.2.1...v1.2.2) (2026-09-07)

### Bug Fixes

* ignore unknown scopes when deserializing micropub tokens
  ([94b8186](https://github.com/jacobsandersen/bastion/commit/94b818639cee2354ee5c8a3774a5680652cf3787))
* reject micropub tokens issued for other identities
  ([49eebee](https://github.com/jacobsandersen/bastion/commit/49eebee7c5a2201e61ff5afcc59d4282a2616d1a))

## [1.2.1](https://github.com/jacobsandersen/bastion/compare/v1.2.0...v1.2.1) (2026-09-07)

### Bug Fixes

* allow graphql schema introspection
  ([f6e9e49](https://github.com/jacobsandersen/bastion/commit/f6e9e49af903045818fe1ce66728c597355a15f5))

## [1.2.0](https://github.com/jacobsandersen/bastion/compare/v1.1.0...v1.2.0) (2026-09-07)

### Features

* classify rsvp webmentions
  ([a7fd161](https://github.com/jacobsandersen/bastion/commit/a7fd161c69db7e8703ea255114a4c1dd162fd592))
* expose webmentions and counts over graphql
  ([d70bc78](https://github.com/jacobsandersen/bastion/commit/d70bc78c5565f5c5623ef67b48dba557998e3efa))

### Bug Fixes

* base webmention undelete dispatch on public content not deletion
  ([efbe6cc](https://github.com/jacobsandersen/bastion/commit/efbe6cc25190d9bc9ef317156b5b097f24e2c42e))
* implement the current graphql coercing api in the json scalar
  ([505569f](https://github.com/jacobsandersen/bastion/commit/505569f6e53362a1866eb629587e3a9ed631ab9e))
* make publiclyReachable account for deleted posts
  ([277e66a](https://github.com/jacobsandersen/bastion/commit/277e66ac8e85ff50579ae4f83300954cbae71edc))

## [1.1.0](https://github.com/jacobsandersen/bastion/compare/v1.0.1...v1.1.0) (2026-09-07)

### Features

* expose published posts over a public graphql api
  ([5c94376](https://github.com/jacobsandersen/bastion/commit/5c94376c99edbeb0fa2523d471f2a05fe4fbe0ad))

## [1.0.1](https://github.com/jacobsandersen/bastion/compare/v1.0.0...v1.0.1) (2026-09-06)

### Bug Fixes

* scope the micropub auth filter to the micropub security chain
  ([88a39a1](https://github.com/jacobsandersen/bastion/commit/88a39a18df8eac11bc83bdd17514ac71245806e0))

## 1.0.0 (2026-09-06)

### Features

* add extractors for webmention text and links
  ([8a5a2a5](https://github.com/jacobsandersen/bastion/commit/8a5a2a5789ece2c541a52703dc6d3b7a94a021e3))
* add generic microformats2 html parser
  ([382cb67](https://github.com/jacobsandersen/bastion/commit/382cb67772a69fe769a126e0f227a00d4bec17c8))
* add received webmention entity and repository
  ([72eac16](https://github.com/jacobsandersen/bastion/commit/72eac160b3030805c85028047995194c18f1f44b))
* add webmention http and discovery helpers
  ([0170cda](https://github.com/jacobsandersen/bastion/commit/0170cda65a37f093ac6e3cb567263f404beece96))
* add webmention http client
  ([5a30f92](https://github.com/jacobsandersen/bastion/commit/5a30f9273415ff122d65ccbb93081fe95d59bc45))
* add webmention receive controller with request verification
  ([60d6227](https://github.com/jacobsandersen/bastion/commit/60d6227e02ba73d3d13dced4869ffa5be4ec06ad))
* add webmention settings
  ([7782ab6](https://github.com/jacobsandersen/bastion/commit/7782ab6f60bd6063b7138d2b4d1fb27a1fb7fa8f))
* detect webmention interaction types from source documents
  ([2a546c3](https://github.com/jacobsandersen/bastion/commit/2a546c31699c44b3e5257295c5595ba7f33acb1f))
* dispatch webmentions on post lifecycle
  ([72e1e1e](https://github.com/jacobsandersen/bastion/commit/72e1e1e3981214bb746e0aead28becab90c7af7b))
* initial commit with working micropub
  ([26618d6](https://github.com/jacobsandersen/bastion/commit/26618d6f662f6437a7e77deb346d900dfc973ba0))
* send and retry webmentions
  ([d9a0ca0](https://github.com/jacobsandersen/bastion/commit/d9a0ca01381dd4f0eef3245dfa5f7e4680a4bf22))
* track webmention notifications
  ([d34d3be](https://github.com/jacobsandersen/bastion/commit/d34d3be0288a33b9ace5f9e941d4ff829d17d41c))
* verify received webmention sources asynchronously
  ([7efc7d4](https://github.com/jacobsandersen/bastion/commit/7efc7d4cd105729ea760082416e8aa954fad8afb))

### Bug Fixes

* classify post types per the post-type discovery algorithm
  ([c34b73d](https://github.com/jacobsandersen/bastion/commit/c34b73de7efbc386bb2ac7f093ad01f40bbf5d10))
* include indieauth response body in token validation logs
  ([f9783fe](https://github.com/jacobsandersen/bastion/commit/f9783febc8991fcd17600ca7501706ed8dc23319))
* include receiver response body in webmention failures
  ([05f42f0](https://github.com/jacobsandersen/bastion/commit/05f42f021514fd4aa888957bde1956f1f14ccd05))
* persist mf2 objects through hibernate json
  ([16e4eeb](https://github.com/jacobsandersen/bastion/commit/16e4eebca2f16642d941c2fbdc57ad5bdef96359))
