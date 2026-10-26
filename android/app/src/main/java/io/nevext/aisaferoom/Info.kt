package io.nevext.aisaferoom

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - Sobre

/** Ajustes → Sobre: the app, its version, the terms, privacy, where to put the camera, and the open-source parts. */
@Composable
fun AboutScreen(onBack: () -> Unit, onTerms: () -> Unit, onPrivacy: () -> Unit, onPlacement: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    Page {
        TopBar("", null, onBack)
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(73.dp)
            Brand(24.sp, Modifier.padding(top = 16.dp))
            Text("Versão $version", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Câmeras de casa com inteligência, sem depender do app nem da nuvem do fabricante.", color = palette.muted,
                fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        Column(Modifier.fillMaxWidth().card(palette)) {
            ListRow(Icons.Outlined.Description, "Termos de uso", "As regras para usar o app", onClick = onTerms)
            ListRow(Icons.Outlined.Lock, "Privacidade", "O que fica guardado, onde e os seus direitos", onClick = onPrivacy)
            ListRow(Icons.Outlined.Place, "Onde pôr a câmera", "Onde não pôr, e o que ajuda a imagem e a IA", onClick = onPlacement)
            ListRow(Icons.Outlined.Code, "Código aberto", "github.com/nevext/ai-safe-room", onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nevext/ai-safe-room"))) }
            })
        }
        Text("Feito com", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().card(palette).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Kotlin e Jetpack Compose (Apache 2.0)",
                "AndroidX Media3 / ExoPlayer, para o vídeo direto da câmera (Apache 2.0)",
                "No computador: Node.js (MIT), ffmpeg (LGPL/GPL), ONNX Runtime (MIT)",
                "RF-DETR, para pessoa e animal (Apache 2.0)",
                "YuNet e SFace do OpenCV Zoo, para rostos (Apache 2.0)",
                "Fonte Geist (SIL Open Font License)",
            ).forEach { Text(it, color = palette.muted, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// MARK: - Termos de uso

/** The terms in short, in the app (the full text is docs/TERMOS.md). */
@Composable
fun TermsScreen(onBack: () -> Unit) {
    Page {
        TopBar("", null, onBack)
        PageTitle("Termos de uso", "O essencial, em poucas linhas")
        InfoCard("O que é", listOf(
            "Um app para ver e controlar as câmeras da sua casa e, se você quiser, usar IA no seu computador para avisar quando aparecer alguém.",
            "Não substitui alarme monitorado, polícia ou bombeiros. Em emergência: 190, 193 ou 192.",
        ))
        InfoCard("Use com responsabilidade", listOf(
            "Só nas câmeras que são suas ou que você tem autorização para usar.",
            "Não filme onde as pessoas esperam privacidade (banheiro, vestiário, quarto de outra pessoa sem ela concordar).",
            "Não aponte para a casa de vizinhos, nem para a rua além do necessário.",
            "Avise quem pode ser filmado (visitas, quem trabalha na casa).",
            "Só cadastre o rosto de alguém com o consentimento dessa pessoa.",
            "Não use para vigiar, perseguir ou controlar alguém sem que a pessoa saiba.",
        ))
        InfoCard("A IA erra", listOf(
            "Pode deixar de avisar, avisar sem ter ninguém ou confundir pessoas. Confira as imagens antes de decidir algo sério.",
        ))
        InfoCard("Funciona com o que você tem", listOf(
            "Depende da câmera, do Wi-Fi, da internet, do celular e, para a IA, do computador ligados.",
            "Nem toda câmera tem todos os recursos: a vistoria mostra o que a sua tem.",
        ))
        InfoCard("Planos", listOf(
            "Hoje é gratuito. Se houver recursos pagos (como gravações na nuvem), preço e cancelamento aparecem antes de assinar.",
        ))
        InfoCard("Responsabilidade", listOf(
            "O app é oferecido como está; não respondemos por falhas da câmera, da rede ou da IA, nem pelo uso das imagens. " +
                "Isso não limita os seus direitos pelo Código de Defesa do Consumidor.",
        ))
        Spacer(Modifier.height(20.dp))
    }
}

// MARK: - Onde pôr a câmera

/** Where not to put a camera (people's privacy, the law) and what helps the picture, the Wi-Fi and the AI. */
@Composable
fun PlacementScreen(onBack: () -> Unit) {
    Page {
        TopBar("", null, onBack)
        PageTitle("Onde pôr a câmera", "Para filmar bem, sem invadir a privacidade de ninguém")
        GuideCard(Icons.Outlined.Block, "Não ponha", listOf(
            "No banheiro ou no vestiário.",
            "No quarto de outra pessoa sem ela concordar (de quem trabalha na casa, de hóspedes, de filhos adolescentes sem conversar antes).",
            "Apontando para a casa ou o quintal dos vizinhos.",
            "Pegando a rua inteira: só o necessário da entrada da sua casa.",
            "Onde filma a tela do computador, o teclado ou o cofre (senhas aparecem na imagem).",
        ), bad = true)
        GuideCard(Icons.Outlined.CheckCircle, "Para a imagem ficar boa", listOf(
            "No alto (2 a 2,5 m), num canto, pegando a porta e a passagem.",
            "Sem uma janela ou luz forte de frente para a lente: contra a luz, as pessoas viram vultos.",
            "Fora de vidro: o infravermelho da visão noturna reflete e a imagem fica branca à noite.",
            "Sem cortina, planta ou ventilador balançando na frente (ou marque como zona para ignorar).",
        ))
        GuideCard(Icons.Outlined.CheckCircle, "Para o Wi-Fi não travar", listOf(
            "Perto do roteador, com no máximo uma parede no caminho; se não der, um repetidor perto da câmera.",
            "Longe do micro-ondas e de paredes grossas ou com espelho.",
            "Teste em Ajustes → Diagnóstico → Wi-Fi da câmera depois de pôr no lugar.",
        ))
        GuideCard(Icons.Outlined.CheckCircle, "Para a IA acertar", listOf(
            "As pessoas devem aparecer inteiras e não muito pequenas: de 2 a 6 metros da câmera é o ideal.",
            "Para reconhecer rostos, a câmera precisa ver as pessoas de frente, como quem entra pela porta.",
            "À noite, a luz branca (se a câmera tiver) ajuda a ver cores e roupas.",
        ))
        InfoCard("Avise quem pode ser filmado", listOf(
            "Uma plaquinha \"ambiente monitorado por câmera\" na entrada basta. Quem trabalha na casa deve saber onde ficam as câmeras.",
        ))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).card(palette).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        lines.forEach { Text(it, color = palette.muted, fontSize = 13.sp) }
    }
}

@Composable
private fun GuideCard(icon: ImageVector, title: String, lines: List<String>, bad: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).card(palette).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (bad) Red else Green, modifier = Modifier.size(20.dp))
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        lines.forEach { Text("•  $it", color = palette.muted, fontSize = 13.sp) }
    }
}
