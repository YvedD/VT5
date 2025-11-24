# Uurlijks Alarm Systeem - Gebruiksinstructies

## Overzicht

Het uurlijkse alarm systeem speelt elk uur op de 59ste minuut een geluid af en brengt de gebruiker naar het HuidigeStandScherm als er een actieve telling is.

## Features

- ✅ Exacte alarm scheduling op de 59ste minuut van elk uur
- ✅ Geluid + vibratie notificatie
- ✅ Automatische integratie met actieve tellingen (TellingScherm)
- ✅ In-/uitschakelbaar via SharedPreferences
- ✅ Herstart automatisch na device reboot
- ✅ Werkt volledig in de achtergrond
- ✅ Fallback naar systeem notificatie geluid

## Technische Implementatie

### Kernbestanden

1. **HourlyAlarmManager.kt** (`core/app/`)
   - Centrale beheerklasse voor het alarm systeem
   - Bevat HourlyAlarmReceiver en BootReceiver

2. **AlarmTestHelper.kt** (`core/app/`)
   - Hulpklasse voor testen en debugging
   - Handmatige trigger functionaliteit

3. **VT5App.kt**
   - Initialiseert alarm bij app start

4. **TellingScherm.kt**
   - onNewIntent handler voor alarm triggers

### SharedPreferences Keys

```kotlin
// In PREFS_NAME = "vt5_prefs"
const val PREF_HOURLY_ALARM_ENABLED = "pref_hourly_alarm_enabled"  // Boolean (standaard: true)
const val PREF_TELLING_ID = "pref_telling_id"                       // String (null = geen actieve telling)
```

## Gebruik in Code

### Basis controle

```kotlin
import com.yvesds.vt5.core.app.HourlyAlarmManager

// Check of alarm is ingeschakeld
val isEnabled = HourlyAlarmManager.isEnabled(context)

// Alarm inschakelen
HourlyAlarmManager.setEnabled(context, true)

// Alarm uitschakelen
HourlyAlarmManager.setEnabled(context, false)
```

### Testing en Debugging

```kotlin
import com.yvesds.vt5.core.app.AlarmTestHelper

// Trigger alarm handmatig (voor test doeleinden)
AlarmTestHelper.triggerAlarmManually(context)

// Haal status informatie op
val status = AlarmTestHelper.getAlarmStatus(context)
Log.d("AlarmStatus", status)

// Reset/herstart het alarm
AlarmTestHelper.resetAlarm(context)
```

## Integratie in Instellingen Scherm

### Voorbeeld UI implementatie

Voor een toekomstig instellingen scherm kun je het volgende gebruiken:

```kotlin
class InstellingenScherm : AppCompatActivity() {
    private lateinit var binding: SchermInstellingenBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermInstellingenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Switch voor uurlijks alarm
        binding.switchUurlijksAlarm.apply {
            isChecked = HourlyAlarmManager.isEnabled(this@InstellingenScherm)
            setOnCheckedChangeListener { _, isChecked ->
                HourlyAlarmManager.setEnabled(this@InstellingenScherm, isChecked)
                Toast.makeText(
                    this@InstellingenScherm,
                    if (isChecked) "Uurlijks alarm ingeschakeld" else "Uurlijks alarm uitgeschakeld",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        // Test button (optioneel, alleen voor debugging)
        if (BuildConfig.DEBUG) {
            binding.btnTestAlarm.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    AlarmTestHelper.triggerAlarmManually(this@InstellingenScherm)
                    Toast.makeText(
                        this@InstellingenScherm,
                        "Test alarm getriggerd",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
```

### Voorbeeld Layout (scherm_instellingen.xml)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">
    
    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Instellingen"
        android:textSize="24sp"
        android:textStyle="bold"
        android:paddingBottom="16dp"/>
    
    <!-- Uurlijks Alarm sectie -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="12dp"
        android:gravity="center_vertical">
        
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical">
            
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Uurlijks alarm"
                android:textSize="16sp"
                android:textStyle="bold"/>
            
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Geluid op de 59ste minuut van elk uur"
                android:textSize="12sp"
                android:alpha="0.7"/>
        </LinearLayout>
        
        <androidx.appcompat.widget.SwitchCompat
            android:id="@+id/switch_uurlijks_alarm"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"/>
    </LinearLayout>
    
    <!-- Test button (alleen zichtbaar in debug builds) -->
    <Button
        android:id="@+id/btn_test_alarm"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Test alarm (debug)"
        android:visibility="gone"/>
</LinearLayout>
```

## Alarm Geluid Aanpassen

### Custom geluid toevoegen

1. Plaats een audio bestand in `app/src/main/res/raw/`
2. Naam het bestand `hourly_alarm.mp3` of `hourly_alarm.ogg`
3. Het geluid moet:
   - Kort zijn (1-3 seconden)
   - Duidelijk hoorbaar maar niet te luid
   - In MP3 of OGG formaat

### Fallback gedrag

Als geen custom geluid aanwezig is, gebruikt het systeem automatisch het standaard notificatie geluid van Android.

## Hoe het werkt

### Alarm Flow

1. **Scheduling**: Bij app start plant VT5App.onCreate() het eerste alarm
2. **59ste minuut**: AlarmManager triggert HourlyAlarmReceiver
3. **Ontvangst**: HourlyAlarmReceiver wordt uitgevoerd:
   - Speelt geluid af
   - Laat apparaat vibreren
   - Check of telling actief is (via PREF_TELLING_ID)
   - Als telling actief: breng TellingScherm naar voorgrond
   - Plan volgend alarm (over ~1 uur)

### TellingScherm integratie

Wanneer een alarm afgaat tijdens een actieve telling:

1. HourlyAlarmReceiver start TellingScherm met speciale Intent extra `SHOW_HUIDIGE_STAND=true`
2. TellingScherm's `onNewIntent()` ontvangt deze Intent
3. TellingScherm roept `handleSaveClose()` aan
4. HuidigeStandScherm wordt geopend met actuele telling data

### Reboot gedrag

Bij device herstart:
1. Android triggert BOOT_COMPLETED broadcast
2. BootReceiver vangt dit op
3. Alarm wordt opnieuw gepland

## Permissies

Het systeem gebruikt de volgende Android permissies:

- `SCHEDULE_EXACT_ALARM` - Voor exacte alarm timing (Android 12+)
- `VIBRATE` - Voor vibratie feedback
- `RECEIVE_BOOT_COMPLETED` - Voor heropstart na reboot

Deze zijn allemaal toegevoegd aan AndroidManifest.xml.

## Debugging

### Log tags

Gebruik deze tags om logs te volgen:

```kotlin
adb logcat | grep -E "HourlyAlarmManager|AlarmTestHelper"
```

### Belangrijke log berichten

- "Volgend alarm gepland voor: [datum/tijd]" - Alarm succesvol gepland
- "Uurlijks alarm ontvangen" - Alarm is afgegaan
- "TellingScherm naar voorgrond gebracht" - Telling integratie werkt
- "Geen actieve telling gevonden" - Alleen geluid/vibratie

### Handmatig testen

Voor snelle tests tijdens development:

```kotlin
// In een test Activity of debug menu:
AlarmTestHelper.triggerAlarmManually(this)
```

Dit simuleert een alarm zonder te wachten op de 59ste minuut.

## Veelvoorkomende problemen

### Alarm gaat niet af

**Oplossingen:**
1. Check of alarm is ingeschakeld: `HourlyAlarmManager.isEnabled(context)`
2. Controleer Android batterij optimalisatie instellingen
3. Voor Android 12+: Check SCHEDULE_EXACT_ALARM permissie
4. Reset alarm: `AlarmTestHelper.resetAlarm(context)`

### Geluid wordt niet afgespeeld

**Oplossingen:**
1. Check volume instellingen van het apparaat
2. Verify dat `hourly_alarm.mp3/.ogg` bestaat in `res/raw/`
3. Check logs voor MediaPlayer errors
4. Fallback zou systeem geluid moeten gebruiken

### TellingScherm wordt niet geopend

**Oplossingen:**
1. Verify dat PREF_TELLING_ID is gezet in SharedPreferences
2. Check of TellingScherm launchMode="singleTop" in manifest
3. Verify dat onNewIntent wordt getriggerd (check logs)

## Toekomstige uitbreidingen

Mogelijke features om toe te voegen:

- [ ] Configureerbare alarm tijden (niet alleen 59ste minuut)
- [ ] Meerdere alarms per uur
- [ ] Custom alarm geluiden selecteren via UI
- [ ] Stille modus (alleen vibratie, geen geluid)
- [ ] Alarm geschiedenis/log
- [ ] Notificatie in plaats van activity launch
- [ ] Geofencing (alleen alarm binnen bepaalde locaties)

## Conclusie

Het uurlijks alarm systeem is volledig geïmplementeerd en klaar voor gebruik. De code is modulair opgezet zodat het gemakkelijk te integreren is in een toekomstig instellingen scherm.

Voor vragen of problemen: check de logs en gebruik de AlarmTestHelper voor debugging.
