# Repair Wave W1-2 — Block C

**Date:** 2026-07-20T00:40:00+02:00
**Role:** repair-fix (validated-findings cluster)

## Findings

### convention-C-3 — ProfileRoomEntity Double-Enum compliance (fixed)

`ProfileRoomEntity` (in `ConfigRoomEntities.kt`) violated the Double-Enum
convention asserted by its own file header: the `style_prompt_mode` /
`system_prompt_mode` / `ambiguity_mode` columns used hardcoded string-literal
defaults (`"PREDEFINED"` / `"ALWAYS_INSERT"`) and exposed no `xxxEnum`
accessor, while the three sibling entities in the same file used `Enum.name`
defaults plus accessors and pushed no parse into the mapper.

**What I did:**

1. `ConfigRoomEntities.kt`
   - Added imports `PromptSelectionMode`, `AmbiguityModeValue`.
   - Replaced the string-literal column defaults with `PromptSelectionMode.PREDEFINED.name`
     (style + system) and `AmbiguityModeValue.ALWAYS_INSERT.name` (ambiguity),
     so an enum rename now reaches the defaults.
   - Added `stylePromptModeEnum`, `systemPromptModeEnum`, `ambiguityModeEnum`
     accessors with the same `runCatching { …valueOf }.getOrDefault(…)` shape
     as the sibling `visibilityEnum` / `subscriptionModeEnum` accessors.
2. `ConfigEntityMapper.kt`
   - `toDto(ProfileRoomEntity, …)` now reads `row.stylePromptModeEnum` /
     `row.systemPromptModeEnum` / `row.ambiguityModeEnum` instead of calling
     private mapper helpers.
   - Removed the now-redundant private `promptSelectionMode(…)` and
     `ambiguityModeValue(…)` helpers — the fallback parse lives in the entity,
     matching the sibling rows (parse is an entity concern, not a mapper one).
   - Removed the now-unused `PromptSelectionMode` and `AmbiguityModeValue`
     imports.

SQL `CHECK` constraints were already present (`MigrationTo12`), so this was a
convention/maintainability fix, not a data-safety fix.

## Tests

`./gradlew :app:testDebugUnitTest --tests "…ConfigEntityMapperTest"` — BUILD
SUCCESSFUL (compilation + ConfigEntityMapperTest green). The app module compiled
cleanly, confirming the entity/mapper change is consistent.

## Files modified

- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/main/java/net/devemperor/dictate/config/entity/ConfigRoomEntities.kt`
- `/home/lukas/WebStorm/Dictate/worktrees/feature/desktop-companion-v1/app/src/main/java/net/devemperor/dictate/config/ConfigEntityMapper.kt`

## Drift

none — both files are in the finding's declared scope. (Note: `ConfigEntityMapper.kt`
carried an unrelated concurrent change from another agent — `sourceRef` →
`sourceRefOrNull` — already on disk when I read it; I did not touch it and it is
not part of my diff.)

## Skipped

none.
