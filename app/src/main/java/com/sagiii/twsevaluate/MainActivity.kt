package com.sagiii.twsevaluate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sagiii.twsevaluate.data.Session
import com.sagiii.twsevaluate.data.SessionRepository
import com.sagiii.twsevaluate.ui.SessionListScreen
import com.sagiii.twsevaluate.ui.theme.TwsEvaluateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwsEvaluateTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TwsEvaluateApp()
                }
            }
        }
    }
}

private const val ROUTE_SESSION_LIST = "sessionList"
private const val ROUTE_NEW_SESSION = "newSession"

@Composable
fun TwsEvaluateApp() {
    val context = LocalContext.current
    val repository = remember { SessionRepository(context.applicationContext) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        sessions = repository.loadAll()
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ROUTE_SESSION_LIST) {
        composable(ROUTE_SESSION_LIST) {
            SessionListScreen(
                sessions = sessions,
                onNewSession = { navController.navigate(ROUTE_NEW_SESSION) },
                onOpenSession = { /* セッション詳細は後続コミットで実装 */ },
            )
        }
        composable(ROUTE_NEW_SESSION) {
            NewSessionPlaceholderScreen(onBack = {
                refreshKey++
                navController.popBackStack()
            })
        }
    }
}

@Composable
private fun NewSessionPlaceholderScreen(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = "商品写真の撮影・評価セッション画面は次のステップで実装します")
    }
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        Button(onClick = onBack) { Text("戻る") }
    }
}
