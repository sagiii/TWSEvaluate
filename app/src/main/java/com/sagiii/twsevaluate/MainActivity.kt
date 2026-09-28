package com.sagiii.twsevaluate

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sagiii.twsevaluate.data.Session
import com.sagiii.twsevaluate.data.SessionRepository
import com.sagiii.twsevaluate.ui.EvaluationSessionScreen
import com.sagiii.twsevaluate.ui.PhotoCaptureScreen
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
private const val ROUTE_CAPTURE = "capture"
private const val ROUTE_EVALUATION = "evaluation/{sessionId}?photo={photo}"

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
                onNewSession = { navController.navigate(ROUTE_CAPTURE) },
                onOpenSession = { /* セッション詳細は後続コミットで実装 */ },
            )
        }
        composable(ROUTE_CAPTURE) {
            PhotoCaptureScreen(
                repository = repository,
                onCaptured = { sessionId, photoPath ->
                    navController.navigate("evaluation/$sessionId?photo=${Uri.encode(photoPath)}") {
                        popUpTo(ROUTE_SESSION_LIST)
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            route = ROUTE_EVALUATION,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("photo") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
            val photoPath = Uri.decode(backStackEntry.arguments?.getString("photo").orEmpty())
            EvaluationSessionScreen(
                sessionId = sessionId,
                photoPath = photoPath,
                repository = repository,
                onFinished = {
                    refreshKey++
                    navController.popBackStack(ROUTE_SESSION_LIST, inclusive = false)
                },
            )
        }
    }
}
