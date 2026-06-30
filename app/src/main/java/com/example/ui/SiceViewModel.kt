package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SiceApplication
import com.example.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

enum class SiceTab {
    Dashboard, Entrada, Baixas, Cadastros, Relatorios
}

enum class SiceSubTab {
    Moradores, Porteiros, Entregadores, GeradorQR
}

class SiceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as SiceApplication).repository

    // Native flow observations from Room
    val moradores = repository.moradores.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val porteiros = repository.porteiros.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entregadores = repository.entregadores.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val encomendas = repository.encomendas.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendentes = repository.pendentes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val blockQrCodes = repository.blockQrCodes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected Navigation Tabs
    var currentTab by mutableStateOf(SiceTab.Dashboard)
    var currentSubTab by mutableStateOf(SiceSubTab.Moradores)

    // ----- STATS -----
    val statPendentesState = encomendas.map { list ->
        list.count { e -> e.status == "Recebido" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val statEntreguesState = encomendas.map { list ->
        list.count { e -> e.status == "Entregue" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val statTotalState = encomendas.map { list ->
        list.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ----- TAB: ENTRADA INTELIGENTE -----
    var barcodeInput by mutableStateOf("")
    var encRastreio by mutableStateOf("")
    var encPlataforma by mutableStateOf("")
    var selectedMorador by mutableStateOf<Morador?>(null)
    var selectedEntregador by mutableStateOf<Entregador?>(null)
    var encBloco by mutableStateOf("")
    var encApto by mutableStateOf("")
    var aiPlatformBadge by mutableStateOf("Scanner Pronto")
    var lastUsedEntregadorName by mutableStateOf("")

    // Camera Scan States
    var isCameraScannerOpen by mutableStateOf(false)
    var isConfirmScanPromptOpen by mutableStateOf(false)

    fun handleCameraScannedCode(code: String) {
        val cleanCode = code.trim().uppercase(Locale.getDefault())
        if (cleanCode.isBlank()) return
        barcodeInput = cleanCode
        processBarcode(cleanCode)
        isCameraScannerOpen = false
        isConfirmScanPromptOpen = true
    }

    fun processBarcode(barcode: String) {
        val code = barcode.trim().uppercase(Locale.getDefault())
        if (code.length < 3) return
        encRastreio = code
        
        when {
            code.startsWith("TBA") -> {
                aiPlatformBadge = "AMAZON LOGS"
                encPlataforma = "Amazon"
            }
            code.startsWith("MELI") || code.startsWith("MLB") || (code.length == 11 && code.all { it.isDigit() }) -> {
                aiPlatformBadge = "MERCADO LIVRE"
                encPlataforma = "Mercado Livre"
            }
            code.startsWith("SHP") || code.startsWith("BR") -> {
                aiPlatformBadge = "SHOPEE APP"
                encPlataforma = "Shopee"
            }
            else -> {
                aiPlatformBadge = "OUTRA LOGÍSTICA"
                encPlataforma = "Outra"
            }
        }
    }

    fun handleMoradorSelection(morador: Morador?) {
        selectedMorador = morador
        if (morador != null) {
            encBloco = morador.bloco
            encApto = morador.apto
        } else {
            encBloco = ""
            encApto = ""
        }
    }

    fun saveEncomenda(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (encRastreio.isBlank()) {
            onError("Código de rastreio de encomenda inválido.")
            return
        }
        val moradorName = selectedMorador?.nome ?: ""
        if (moradorName.isBlank()) {
            onError("Selecione um morador destinatário.")
            return
        }
        val entregadorName = selectedEntregador?.nome ?: ""
        if (entregadorName.isBlank()) {
            onError("Selecione o entregador.")
            return
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val now = Date()

        val item = Encomenda(
            rastreio = encRastreio,
            plataforma = encPlataforma,
            bloco = encBloco.uppercase(Locale.getDefault()),
            apto = encApto,
            morador = moradorName,
            entregador = entregadorName,
            status = "Recebido",
            dataEntrada = dateFormat.format(now),
            horaEntrada = timeFormat.format(now)
        )

        viewModelScope.launch {
            repository.insertEncomenda(item)
            lastUsedEntregadorName = entregadorName
            // Reset fields
            barcodeInput = ""
            encRastreio = ""
            encPlataforma = ""
            selectedMorador = null
            encBloco = ""
            encApto = ""
            aiPlatformBadge = "Scanner Pronto"
            onSuccess()
        }
    }

    // ----- TAB: BAIXA POR BLOCO -----
    var baixaSelectedBloco by mutableStateOf("")
    var baixaSelectedPorteiro by mutableStateOf<Porteiro?>(null)
    var selectedEncomendaIds = mutableStateOf<Set<Int>>(emptySet())
    var isQrModalOpen by mutableStateOf(false)
    var qrModalInputText by mutableStateOf("")

    fun triggerBlockSelection(bloco: String) {
        baixaSelectedBloco = bloco
        selectedEncomendaIds.value = emptySet()
        // Auto-select match and suggested portier
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val targetShift = if (hour >= 18 || hour < 6) "Noite" else "Manhã"
        val suggested = porteiros.value.find { 
            it.bloco.equals(bloco, ignoreCase = true) && it.turno == targetShift 
        }
        baixaSelectedPorteiro = suggested ?: porteiros.value.find { 
            it.bloco.equals(bloco, ignoreCase = true) 
        }
    }

    fun toggleCheckAllPending(isCheckAll: Boolean) {
        val blocPending = encomendas.value.filter { it.status == "Recebido" && it.bloco == baixaSelectedBloco }
        if (isCheckAll) {
            selectedEncomendaIds.value = blocPending.map { it.id }.toSet()
        } else {
            selectedEncomendaIds.value = emptySet()
        }
    }

    fun toggleCheckEncomenda(id: Int) {
        val currentSet = selectedEncomendaIds.value.toMutableSet()
        if (currentSet.contains(id)) {
            currentSet.remove(id)
        } else {
            currentSet.add(id)
        }
        selectedEncomendaIds.value = currentSet
    }

    fun performValidationQR(tokenScanned: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val cleanToken = tokenScanned.trim()
        if (cleanToken.isEmpty()) return
        
        viewModelScope.launch {
            val correctToken = dbSelectedBlockToken(baixaSelectedBloco)
            
            // Allow perfect token match OR short bloco name matched OR inclusion of block code in token string
            val isAuthorized = cleanToken == correctToken || 
                               cleanToken.equals(baixaSelectedBloco, ignoreCase = true) || 
                               cleanToken.contains("-${baixaSelectedBloco.uppercase()}-")
            
            if (isAuthorized) {
                // Confirm Checkout
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val now = Date()
                val dateStr = dateFormat.format(now).split("-").reversed().joinToString("/")
                val timeStr = timeFormat.format(now)

                val portierName = baixaSelectedPorteiro?.nome ?: "Portaria Central"

                // Update checked items in Room Database
                val itemsToUpdate = encomendas.value.filter { selectedEncomendaIds.value.contains(it.id) }
                itemsToUpdate.forEach { item ->
                    val updated = item.copy(
                        status = "Entregue",
                        dataEntrega = dateFormat.format(now),
                        horaEntrega = timeFormat.format(now),
                        porteiroEntrega = portierName,
                        assinaturaRaw = cleanToken
                    )
                    repository.insertEncomenda(updated)
                }

                // Clean states
                selectedEncomendaIds.value = emptySet()
                isQrModalOpen = false
                qrModalInputText = ""
                onSuccess()
            } else {
                onError("Acesso Recusado! Esse QRCode lido não pertence ao Bloco $baixaSelectedBloco. Tente novamente.")
            }
        }
    }

    private suspend fun dbSelectedBlockToken(bloco: String): String {
        return repository.getBlockQrCode(bloco)?.token ?: "$bloco-0000"
    }

    // ----- TAB: CADASTROS - MORADORES -----
    var moradorEditId by mutableStateOf<Int?>(null)
    var moradorNomeInput by mutableStateOf("")
    var moradorBlocoInput by mutableStateOf("")
    var moradorAptoInput by mutableStateOf("")

    fun startEditMorador(m: Morador) {
        moradorEditId = m.id
        moradorNomeInput = m.nome
        moradorBlocoInput = m.bloco
        moradorAptoInput = m.apto
    }

    fun cancelEditMorador() {
        moradorEditId = null
        moradorNomeInput = ""
        moradorBlocoInput = ""
        moradorAptoInput = ""
    }

    fun saveMorador(onComplete: () -> Unit) {
        if (moradorNomeInput.isBlank() || moradorBlocoInput.isBlank() || moradorAptoInput.isBlank()) return
        viewModelScope.launch {
            val item = Morador(
                id = moradorEditId ?: 0,
                nome = moradorNomeInput.trim(),
                bloco = moradorBlocoInput.trim().uppercase(Locale.getDefault()),
                apto = moradorAptoInput.trim()
            )
            repository.insertMorador(item)
            cancelEditMorador()
            onComplete()
        }
    }

    fun deleteMorador(id: Int) {
        viewModelScope.launch {
            repository.deleteMoradorById(id)
        }
    }

    // ----- TAB: CADASTROS - PORTEIROS -----
    var porteiroEditId by mutableStateOf<Int?>(null)
    var porteiroNomeInput by mutableStateOf("")
    var porteiroTurnoInput by mutableStateOf("Manhã")
    var porteiroBlocoInput by mutableStateOf("")

    fun startEditPorteiro(p: Porteiro) {
        porteiroEditId = p.id
        porteiroNomeInput = p.nome
        porteiroTurnoInput = p.turno
        porteiroBlocoInput = p.bloco
    }

    fun cancelEditPorteiro() {
        porteiroEditId = null
        porteiroNomeInput = ""
        porteiroTurnoInput = "Manhã"
        porteiroBlocoInput = ""
    }

    fun savePorteiro(onComplete: () -> Unit) {
        if (porteiroNomeInput.isBlank() || porteiroBlocoInput.isBlank()) return
        viewModelScope.launch {
            val item = Porteiro(
                id = porteiroEditId ?: 0,
                nome = porteiroNomeInput.trim(),
                turno = porteiroTurnoInput,
                bloco = porteiroBlocoInput.trim().uppercase(Locale.getDefault())
            )
            repository.insertPorteiro(item)
            cancelEditPorteiro()
            onComplete()
        }
    }

    fun deletePorteiro(id: Int) {
        viewModelScope.launch {
            repository.deletePorteiroById(id)
        }
    }

    // ----- TAB: CADASTROS - ENTREGADORES -----
    var entregadorEditId by mutableStateOf<Int?>(null)
    var entregadorNomeInput by mutableStateOf("")
    var entregadorEmpresaInput by mutableStateOf("")

    fun startEditEntregador(e: Entregador) {
        entregadorEditId = e.id
        entregadorNomeInput = e.nome
        entregadorEmpresaInput = e.empresa
    }

    fun cancelEditEntregador() {
        entregadorEditId = null
        entregadorNomeInput = ""
        entregadorEmpresaInput = ""
    }

    fun saveEntregador(onComplete: () -> Unit) {
        if (entregadorNomeInput.isBlank() || entregadorEmpresaInput.isBlank()) return
        viewModelScope.launch {
            val item = Entregador(
                id = entregadorEditId ?: 0,
                nome = entregadorNomeInput.trim(),
                empresa = entregadorEmpresaInput.trim()
            )
            repository.insertEntregador(item)
            cancelEditEntregador()
            onComplete()
        }
    }

    fun deleteEntregador(id: Int) {
        viewModelScope.launch {
            repository.deleteEntregadorById(id)
        }
    }

    // ----- TAB: CADASTROS - GERADOR DE QRCODES -----
    var qrSenhaUnlocked by mutableStateOf(false)
    var qrSenhaInput by mutableStateOf("")
    var qrBlocoNomeInput by mutableStateOf("")
    var generatedQrToken by mutableStateOf<String?>(null)
    var generatedQrBloco by mutableStateOf<String?>(null)

    fun resetGeradorState() {
        qrSenhaUnlocked = false
        qrSenhaInput = ""
        qrBlocoNomeInput = ""
        generatedQrToken = null
        generatedQrBloco = null
    }

    fun checkQrPassword(onSuccess: () -> Unit, onError: () -> Unit) {
        if (qrSenhaInput == "1234") {
            qrSenhaUnlocked = true
            onSuccess()
        } else {
            onError()
        }
    }

    fun generateBlockQr() {
        val bloco = qrBlocoNomeInput.trim().uppercase(Locale.getDefault())
        if (bloco.isEmpty()) return
        
        viewModelScope.launch {
            val existing = repository.getBlockQrCode(bloco)
            val token = existing?.token ?: run {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                val numAleatorio = (1..5).map { chars.random() }.joinToString("")
                val newToken = "$bloco-$numAleatorio"
                val blockQr = BlockQrCode(bloco = bloco, token = newToken)
                repository.insertBlockQrCode(blockQr)
                newToken
            }
            generatedQrToken = token
            generatedQrBloco = bloco
        }
    }

    // ----- TAB: RELATORIOS -----
    var filtroDataIni by mutableStateOf("")
    var filtroDataFim by mutableStateOf("")
    var filtroBloco by mutableStateOf("")
    var filtroApto by mutableStateOf("")

    // List filtered reactively from combine
    private val _filtroDataIniFlow = MutableStateFlow("")
    private val _filtroDataFimFlow = MutableStateFlow("")
    private val _filtroBlocoFlow = MutableStateFlow("")
    private val _filtroAptoFlow = MutableStateFlow("")

    fun applyFilters(ini: String, fim: String, bloco: String, apto: String) {
        filtroDataIni = ini
        filtroDataFim = fim
        filtroBloco = bloco
        filtroApto = apto
        viewModelScope.launch {
            _filtroDataIniFlow.value = ini
            _filtroDataFimFlow.value = fim
            _filtroBlocoFlow.value = bloco
            _filtroAptoFlow.value = apto
        }
    }

    val reportList = combine(
        encomendas, _filtroDataIniFlow, _filtroDataFimFlow, _filtroBlocoFlow, _filtroAptoFlow
    ) { list, ini, fim, bloco, apto ->
        list.filter { item ->
            if (ini.isNotEmpty() && item.dataEntrada < ini) return@filter false
            if (fim.isNotEmpty() && item.dataEntrada > fim) return@filter false
            if (bloco.isNotEmpty() && item.bloco != bloco) return@filter false
            if (apto.isNotEmpty() && !item.apto.equals(apto, ignoreCase = true)) return@filter false
            true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Advanced Text Export
    fun exportReportToTxt(context: Context, onComplete: (String) -> Unit) {
        val currentList = reportList.value
        val stringBuilder = StringBuilder()
        
        stringBuilder.append("========================================================================================\n")
        stringBuilder.append("                      CONDENTREGAS - RELATÓRIO DE ENTREGAS E ENCOMENDAS                         \n")
        stringBuilder.append("========================================================================================\n")
        stringBuilder.append(String.format("%-19s | %-13s | %-5s | %-4s | %-20s | %-12s | %-15s\n", 
            "Data/Hora Entrada", "Plataforma", "Bloco", "Apto", "Morador", "Status", "Entregador"))
        stringBuilder.append("----------------------------------------------------------------------------------------\n")
        
        currentList.forEach { e ->
            val dateFmt = e.dataEntrada.split("-").reversed().joinToString("/") + " " + e.horaEntrada.take(5)
            val moradorCut = if (e.morador.length > 20) e.morador.take(17) + "..." else e.morador
            val platformCut = if (e.plataforma.length > 13) e.plataforma.take(10) + "..." else e.plataforma
            val entregadorCut = if (e.entregador.length > 15) e.entregador.take(12) + "..." else e.entregador
            
            stringBuilder.append(String.format("%-19s | %-13s | %-5s | %-4s | %-20s | %-12s | %-15s\n",
                dateFmt, platformCut, e.bloco, e.apto, moradorCut, e.status, entregadorCut))
            if (e.status == "Entregue") {
                stringBuilder.append(String.format("   ↳ [Saída] em %s %s por %s\n", e.dataEntrega.split("-").reversed().joinToString("/"), e.horaEntrega.take(5), e.porteiroEntrega))
                stringBuilder.append(String.format("   ↳ [Assinatura] %s\n", e.assinaturaRaw))
            }
        }
        stringBuilder.append("========================================================================================\n")
        stringBuilder.append("Exportado em: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        
        viewModelScope.launch {
            try {
                val directory = context.cacheDir
                val file = File(directory, "relatorio_entregas.txt")
                val writer = FileWriter(file)
                writer.write(stringBuilder.toString())
                writer.close()

                // Share file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Relatório Geral de Entregas")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooserIntent = Intent.createChooser(shareIntent, "Salvar/Compartilhar Relatório")
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooserIntent)
                onComplete("Relatório gerado! Escolha como salvar ou compartilhar.")
            } catch (e: Exception) {
                onComplete("Erro ao salvar arquivo: ${e.localizedMessage}")
            }
        }
    }
}
