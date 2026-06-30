package com.example.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// ------------------ ENTITIES ------------------

@Entity(tableName = "moradores")
data class Morador(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nome: String,
    val bloco: String,
    val apto: String
)

@Entity(tableName = "porteiros")
data class Porteiro(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nome: String,
    val turno: String, // "Manhã" or "Noite"
    val bloco: String
)

@Entity(tableName = "entregadores")
data class Entregador(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nome: String,
    val empresa: String
)

@Entity(tableName = "encomendas")
data class Encomenda(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val rastreio: String,
    val plataforma: String,
    val bloco: String,
    val apto: String,
    val morador: String,
    val entregador: String,
    val status: String, // "Recebido" or "Entregue"
    val dataEntrada: String, // YYYY-MM-DD
    val horaEntrada: String, // HH:MM:SS
    val dataEntrega: String = "",
    val horaEntrega: String = "",
    val porteiroEntrega: String = "",
    val assinaturaRaw: String = ""
)

@Entity(tableName = "block_qrcodes")
data class BlockQrCode(
    @PrimaryKey val bloco: String, // e.g. "A", "B"
    val token: String
)

@Entity(tableName = "web_data")
data class WebData(
    @PrimaryKey val keyName: String,
    val jsonValue: String
)

// ------------------ DAO ------------------

@Dao
interface SiceDao {
    // Moradores
    @Query("DELETE FROM moradores")
    suspend fun deleteAllMoradores()

    @Query("DELETE FROM porteiros")
    suspend fun deleteAllPorteiros()

    @Query("DELETE FROM entregadores")
    suspend fun deleteAllEntregadores()

    @Query("DELETE FROM encomendas")
    suspend fun deleteAllEncomendas()

    @Query("SELECT * FROM moradores ORDER BY nome ASC")
    fun getAllMoradores(): Flow<List<Morador>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMorador(morador: Morador)

    @Update
    suspend fun updateMorador(morador: Morador)

    @Query("DELETE FROM moradores WHERE id = :id")
    suspend fun deleteMoradorById(id: Int)

    // Porteiros
    @Query("SELECT * FROM porteiros ORDER BY nome ASC")
    fun getAllPorteiros(): Flow<List<Porteiro>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPorteiro(porteiro: Porteiro)

    @Update
    suspend fun updatePorteiro(porteiro: Porteiro)

    @Query("DELETE FROM porteiros WHERE id = :id")
    suspend fun deletePorteiroById(id: Int)

    // Entregadores
    @Query("SELECT * FROM entregadores ORDER BY nome ASC")
    fun getAllEntregadores(): Flow<List<Entregador>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntregador(entregador: Entregador)

    @Update
    suspend fun updateEntregador(entregador: Entregador)

    @Query("DELETE FROM entregadores WHERE id = :id")
    suspend fun deleteEntregadorById(id: Int)

    // Encomendas
    @Query("SELECT * FROM encomendas ORDER BY id DESC")
    fun getAllEncomendas(): Flow<List<Encomenda>>

    @Query("SELECT * FROM encomendas WHERE status = 'Recebido' ORDER BY id DESC")
    fun getPendentes(): Flow<List<Encomenda>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEncomenda(encomenda: Encomenda)

    @Update
    suspend fun updateEncomenda(encomenda: Encomenda)

    // BlockQrCode
    @Query("SELECT * FROM block_qrcodes")
    fun getAllBlockQrCodes(): Flow<List<BlockQrCode>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockQrCode(blockQrCode: BlockQrCode)
    
    @Query("SELECT * FROM block_qrcodes WHERE bloco = :bloco LIMIT 1")
    suspend fun getBlockQrCode(bloco: String): BlockQrCode?

    // WebData
    @Query("SELECT jsonValue FROM web_data WHERE keyName = :keyName LIMIT 1")
    suspend fun getWebData(keyName: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWebData(data: WebData)
}

// ------------------ DATABASE ------------------

@Database(
    entities = [Morador::class, Porteiro::class, Entregador::class, Encomenda::class, BlockQrCode::class, WebData::class],
    version = 4,
    exportSchema = false
)
abstract class SiceDatabase : RoomDatabase() {
    abstract fun siceDao(): SiceDao

    companion object {
        @Volatile
        private var INSTANCE: SiceDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): SiceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SiceDatabase::class.java,
                    "entregas.db"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(SiceDatabaseCallback(scope))
                .build()
                INSTANCE = instance
                instance
            }
        }

        fun closeAndResetDatabase() {
            synchronized(this) {
                try {
                    INSTANCE?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                INSTANCE = null
            }
        }
    }

    private class SiceDatabaseCallback(
        private val scope: CoroutineScope
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                scope.launch(Dispatchers.IO) {
                    populateDatabase(database.siceDao())
                }
            }
        }

        private suspend fun populateDatabase(dao: SiceDao) {
            // Default block check qrcodes
            dao.insertBlockQrCode(BlockQrCode(bloco = "A", token = "BLOCK-A-9912"))
            dao.insertBlockQrCode(BlockQrCode(bloco = "B", token = "BLOCK-B-4321"))
        }
    }
}

// ------------------ REPOSITORY ------------------

class SiceRepository(private val dao: SiceDao) {
    val moradores: Flow<List<Morador>> = dao.getAllMoradores()
    val porteiros: Flow<List<Porteiro>> = dao.getAllPorteiros()
    val entregadores: Flow<List<Entregador>> = dao.getAllEntregadores()
    val encomendas: Flow<List<Encomenda>> = dao.getAllEncomendas()
    val pendentes: Flow<List<Encomenda>> = dao.getPendentes()
    val blockQrCodes: Flow<List<BlockQrCode>> = dao.getAllBlockQrCodes()

    suspend fun deleteAllMoradores() = dao.deleteAllMoradores()
    suspend fun deleteAllPorteiros() = dao.deleteAllPorteiros()
    suspend fun deleteAllEntregadores() = dao.deleteAllEntregadores()
    suspend fun deleteAllEncomendas() = dao.deleteAllEncomendas()

    suspend fun insertMorador(m: Morador) = dao.insertMorador(m)
    suspend fun updateMorador(m: Morador) = dao.updateMorador(m)
    suspend fun deleteMoradorById(id: Int) = dao.deleteMoradorById(id)

    suspend fun insertPorteiro(p: Porteiro) = dao.insertPorteiro(p)
    suspend fun updatePorteiro(p: Porteiro) = dao.updatePorteiro(p)
    suspend fun deletePorteiroById(id: Int) = dao.deletePorteiroById(id)

    suspend fun insertEntregador(e: Entregador) = dao.insertEntregador(e)
    suspend fun updateEntregador(e: Entregador) = dao.updateEntregador(e)
    suspend fun deleteEntregadorById(id: Int) = dao.deleteEntregadorById(id)

    suspend fun insertEncomenda(e: Encomenda) = dao.insertEncomenda(e)
    suspend fun updateEncomenda(e: Encomenda) = dao.updateEncomenda(e)

    suspend fun insertBlockQrCode(qr: BlockQrCode) = dao.insertBlockQrCode(qr)
    suspend fun getBlockQrCode(bloco: String) = dao.getBlockQrCode(bloco)

    // WebData
    suspend fun getWebData(keyName: String) = dao.getWebData(keyName)
    suspend fun insertWebData(data: WebData) = dao.insertWebData(data)
}
