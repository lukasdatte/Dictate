package net.devemperor.dictate.config.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.devemperor.dictate.config.entity.ApiCredentialRoomEntity
import net.devemperor.dictate.config.entity.ModelRefRoomEntity
import net.devemperor.dictate.config.entity.ProfilePromptRoomEntity
import net.devemperor.dictate.config.entity.ProfileRoomEntity
import net.devemperor.dictate.config.entity.ProviderConfigRoomEntity

/**
 * DAOs for the config-entity tables (spec §7.4). All enum columns are `String` (Double-Enum rule);
 * the write path is [net.devemperor.dictate.config.ConfigRepository], the read path is
 * [net.devemperor.dictate.ai.adapter.ProfileResolver].
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §7.4
 */
@Dao
interface ProviderConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ProviderConfigRoomEntity)

    @Query("SELECT * FROM provider_configs WHERE id = :id")
    fun byId(id: String): ProviderConfigRoomEntity?

    @Query("SELECT * FROM provider_configs ORDER BY label ASC")
    fun getAll(): List<ProviderConfigRoomEntity>

    @Query("SELECT COUNT(*) FROM provider_configs")
    fun count(): Int

    @Query("DELETE FROM provider_configs WHERE id = :id")
    fun deleteById(id: String)
}

@Dao
interface ApiCredentialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ApiCredentialRoomEntity)

    @Query("SELECT * FROM api_credentials WHERE id = :id")
    fun byId(id: String): ApiCredentialRoomEntity?

    @Query("SELECT * FROM api_credentials ORDER BY label ASC")
    fun getAll(): List<ApiCredentialRoomEntity>

    @Query("SELECT COUNT(*) FROM api_credentials")
    fun count(): Int

    @Query("DELETE FROM api_credentials WHERE id = :id")
    fun deleteById(id: String)
}

@Dao
interface ModelRefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ModelRefRoomEntity)

    @Query("SELECT * FROM model_refs WHERE id = :id")
    fun byId(id: String): ModelRefRoomEntity?

    @Query("SELECT * FROM model_refs WHERE provider_ref = :providerRef ORDER BY model_id ASC")
    fun byProvider(providerRef: String): List<ModelRefRoomEntity>

    @Query("SELECT * FROM model_refs ORDER BY model_id ASC")
    fun getAll(): List<ModelRefRoomEntity>

    @Query("SELECT COUNT(*) FROM model_refs")
    fun count(): Int

    @Query("DELETE FROM model_refs WHERE id = :id")
    fun deleteById(id: String)

    @Query("DELETE FROM model_refs WHERE provider_ref = :providerRef")
    fun deleteByProvider(providerRef: String)
}

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertProfile(entity: ProfileRoomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProfilePrompts(rows: List<ProfilePromptRoomEntity>)

    @Query("DELETE FROM profile_prompts WHERE profile_id = :profileId")
    fun deleteProfilePrompts(profileId: String)

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun byId(id: String): ProfileRoomEntity?

    @Query("SELECT * FROM profile_prompts WHERE profile_id = :profileId ORDER BY pos ASC")
    fun promptsOf(profileId: String): List<ProfilePromptRoomEntity>

    @Query("SELECT * FROM profiles ORDER BY name ASC")
    fun getAll(): List<ProfileRoomEntity>

    @Query("SELECT COUNT(*) FROM profiles")
    fun count(): Int

    /** `profile_prompts` rows fall with the profile via the CASCADE FK. */
    @Query("DELETE FROM profiles WHERE id = :id")
    fun deleteById(id: String)
}
