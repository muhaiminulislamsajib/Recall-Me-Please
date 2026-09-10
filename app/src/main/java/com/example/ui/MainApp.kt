package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.repository.AppUser
import com.example.data.repository.AuthRepository
import com.example.data.repository.MemoryRepository
import com.example.ui.screens.AccountScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MemoriesScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.IndigoDark
import com.example.ui.theme.IndigoPrimary

enum class AppDestination(val label: String, val icon: ImageVector, val tag: String) {
    HOME("Home", Icons.Default.Home, "nav_home"),
    MEMORIES("Memories", Icons.AutoMirrored.Filled.Notes, "nav_memories"),
    SEARCH("Search", Icons.Default.Search, "nav_search"),
    ACCOUNT("Account", Icons.Default.Person, "nav_account")
}

@Composable
fun MainApp(
    authRepository: AuthRepository,
    memoryRepository: MemoryRepository
) {
    val currentUser by authRepository.currentUser.collectAsState()
    var guestUser by remember { mutableStateOf<AppUser?>(null) }

    val activeUser = currentUser ?: guestUser

    if (activeUser == null) {
        AuthScreen(
            authRepository = authRepository,
            onAuthSuccess = { user ->
                guestUser = user
            }
        )
    } else {
        var currentDestination by remember { mutableStateOf(AppDestination.HOME) }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    AppDestination.entries.forEach { destination ->
                        val isSelected = currentDestination == destination
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentDestination = destination },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = IndigoPrimary,
                                selectedTextColor = IndigoPrimary,
                                indicatorColor = IndigoPrimary.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.testTag(destination.tag)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentDestination) {
                    AppDestination.HOME -> HomeScreen(
                        userId = activeUser.uid,
                        memoryRepository = memoryRepository,
                        onNavigateToMemories = { currentDestination = AppDestination.MEMORIES }
                    )
                    AppDestination.MEMORIES -> MemoriesScreen(
                        userId = activeUser.uid,
                        memoryRepository = memoryRepository
                    )
                    AppDestination.SEARCH -> SearchScreen(
                        userId = activeUser.uid,
                        memoryRepository = memoryRepository
                    )
                    AppDestination.ACCOUNT -> AccountScreen(
                        user = activeUser,
                        authRepository = authRepository,
                        memoryRepository = memoryRepository,
                        onSignedOut = {
                            guestUser = null
                        }
                    )
                }
            }
        }
    }
}
