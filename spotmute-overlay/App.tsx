import React, {useCallback, useEffect, useState} from 'react';
import {
  AppState,
  NativeEventEmitter,
  NativeModules,
  Platform,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';

type State = 'off' | 'idle' | 'music' | 'ad' | 'ad-paused';

type Status = {
  listenerEnabled: boolean;
  listenerConnected: boolean;
  spotifyFound: boolean;
  state: State;
  playing: boolean;
  mutedByUs: boolean;
  title: string;
  artist: string;
  album: string;
  score: number;
  signals: string[];
};

type Config = {enabled: boolean; adVolumePercent: number; extraTitles: string};

type SpotMuteNative = {
  getStatus(): Promise<Status>;
  getConfig(): Promise<Config>;
  getLog(): Promise<string[]>;
  setEnabled(v: boolean): void;
  setAdVolumePercent(v: number): void;
  setExtraTitles(v: string): void;
  restoreVolumeNow(): Promise<string>;
  openListenerSettings(): void;
  openAppInfo(): void;
  requestRebind(): void;
};

const native = NativeModules.SpotMute as SpotMuteNative | undefined;

const EMPTY: Status = {
  listenerEnabled: false,
  listenerConnected: false,
  spotifyFound: false,
  state: 'idle',
  playing: false,
  mutedByUs: false,
  title: '',
  artist: '',
  album: '',
  score: 0,
  signals: [],
};

const STATE_LABEL: Record<State, string> = {
  off: 'Switched off',
  idle: 'Waiting for Spotify',
  music: 'Music (not muted)',
  ad: 'Ad detected - muted',
  'ad-paused': 'Ad detected, paused',
};

const STATE_COLOR: Record<State, string> = {
  off: '#6b7280',
  idle: '#6b7280',
  music: '#22c55e',
  ad: '#f59e0b',
  'ad-paused': '#f59e0b',
};

export default function App() {
  const [status, setStatus] = useState<Status>(EMPTY);
  const [config, setConfig] = useState<Config>({enabled: true, adVolumePercent: 0, extraTitles: ''});
  const [extra, setExtra] = useState('');
  const [log, setLog] = useState<string[]>([]);
  const [note, setNote] = useState('');

  const refresh = useCallback(async () => {
    if (!native) {
      return;
    }
    setStatus(await native.getStatus());
    setLog(await native.getLog());
  }, []);

  useEffect(() => {
    if (!native) {
      return;
    }
    native.getConfig().then(c => {
      setConfig(c);
      setExtra(c.extraTitles);
    });
    refresh();

    const emitter = new NativeEventEmitter(NativeModules.SpotMute);
    const sub = emitter.addListener('SpotMuteStatus', (s: any) => {
      setStatus(s as Status);
      native.getLog().then(setLog);
    });
    // Coming back from the Android settings screen: re-check the permission.
    const appSub = AppState.addEventListener('change', s => {
      if (s === 'active') {
        refresh();
      }
    });
    return () => {
      sub.remove();
      appSub.remove();
    };
  }, [refresh]);

  if (Platform.OS !== 'android' || !native) {
    return (
      <View style={styles.center}>
        <Text style={styles.text}>SpotMute needs its native Android module. Build it for Android as described in the README.</Text>
      </View>
    );
  }

  const needsAccess = !status.listenerEnabled;
  const needsRebind = status.listenerEnabled && !status.listenerConnected;

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <StatusBar barStyle="light-content" />
      <Text style={styles.title}>SpotMute</Text>
      <Text style={styles.sub}>Best-effort ad muting for Spotify on Android</Text>

      {needsAccess && (
        <View style={[styles.card, styles.warn]}>
          <Text style={styles.cardTitle}>1. Allow notification access</Text>
          <Text style={styles.text}>
            SpotMute reads Spotify's media session and notification to guess when an ad plays. Nothing leaves your phone.
            On Android 13+ you first need "Allow restricted settings" in the app info page (⋮ menu), because this APK is sideloaded.
          </Text>
          <View style={styles.row}>
            <Btn label="Open app info" onPress={() => native.openAppInfo()} />
            <Btn label="Notification access" primary onPress={() => native.openListenerSettings()} />
          </View>
        </View>
      )}

      {needsRebind && (
        <View style={[styles.card, styles.warn]}>
          <Text style={styles.cardTitle}>Service not connected</Text>
          <Text style={styles.text}>Access is granted but Android has not started the listener yet.</Text>
          <Btn label="Reconnect" primary onPress={() => native.requestRebind()} />
        </View>
      )}

      <View style={styles.card}>
        <View style={styles.rowBetween}>
          <Text style={styles.cardTitle}>Mute Spotify ads</Text>
          <Switch
            value={config.enabled}
            onValueChange={v => {
              setConfig({...config, enabled: v});
              native.setEnabled(v);
            }}
          />
        </View>
        <View style={styles.pillRow}>
          <View style={[styles.dot, {backgroundColor: STATE_COLOR[status.state]}]} />
          <Text style={styles.stateText}>{STATE_LABEL[status.state]}</Text>
        </View>
        <Text style={styles.small}>
          Spotify session: {status.spotifyFound ? 'found' : 'not found'} · Playing: {status.playing ? 'yes' : 'no'} · Volume lowered by
          SpotMute: {status.mutedByUs ? 'yes' : 'no'}
        </Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>What Spotify is reporting</Text>
        <Text style={styles.mono}>title : {status.title || '-'}</Text>
        <Text style={styles.mono}>artist: {status.artist || '-'}</Text>
        <Text style={styles.mono}>album : {status.album || '-'}</Text>
        <Text style={styles.mono}>
          ad score: {status.score} (needs 3){status.signals.length ? '\n' + status.signals.join('\n') : ''}
        </Text>
        <Text style={styles.small}>
          If an ad slips through, play one and note the title/artist shown here, then add that title below.
        </Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>During an ad, set volume to</Text>
        <View style={styles.row}>
          {[0, 10, 20].map(p => (
            <Btn
              key={p}
              label={p === 0 ? 'Mute' : `${p}%`}
              primary={config.adVolumePercent === p}
              onPress={() => {
                setConfig({...config, adVolumePercent: p});
                native.setAdVolumePercent(p);
              }}
            />
          ))}
        </View>
        <Text style={styles.small}>After the ad, your original volume is put back (unless you changed it yourself meanwhile).</Text>

        <Text style={[styles.cardTitle, styles.gap]}>Extra ad titles</Text>
        <TextInput
          style={styles.input}
          value={extra}
          onChangeText={setExtra}
          placeholder="comma separated, e.g. Anuncio, Werbung"
          placeholderTextColor="#6b7280"
          autoCapitalize="none"
          autoCorrect={false}
        />
        <Btn
          label="Save titles"
          onPress={() => {
            native.setExtraTitles(extra);
            setNote('Saved.');
          }}
        />
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Safety</Text>
        <Btn
          label="Restore my volume now"
          onPress={async () => setNote(await native.restoreVolumeNow())}
        />
        {!!note && <Text style={styles.small}>{note}</Text>}
        <Text style={styles.small}>If an ad is still playing, SpotMute may lower the volume again on its next update. Switch it off first if that is not what you want.</Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Event log</Text>
        {log.length === 0 ? <Text style={styles.small}>Nothing yet.</Text> : log.map((l, i) => <Text key={i} style={styles.mono}>{l}</Text>)}
      </View>

      <Text style={styles.footer}>
        Detection is a heuristic, not a guarantee. It can miss ads, and it can occasionally lower the volume during a normal
        track. It changes the whole phone's media volume, so other apps are muted too while an ad is detected.
      </Text>
    </ScrollView>
  );
}

function Btn({label, onPress, primary}: {label: string; onPress: () => void; primary?: boolean}) {
  return (
    <TouchableOpacity style={[styles.btn, primary && styles.btnPrimary]} onPress={onPress}>
      <Text style={[styles.btnText, primary && styles.btnTextPrimary]}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#0b0f14'},
  content: {padding: 16, paddingTop: (StatusBar.currentHeight ?? 24) + 12, paddingBottom: 40},
  center: {flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, backgroundColor: '#0b0f14'},
  title: {color: '#fff', fontSize: 28, fontWeight: '700'},
  sub: {color: '#9ca3af', marginBottom: 16},
  card: {backgroundColor: '#141a22', borderRadius: 12, padding: 14, marginBottom: 12},
  warn: {borderWidth: 1, borderColor: '#f59e0b'},
  cardTitle: {color: '#fff', fontSize: 16, fontWeight: '600', marginBottom: 6},
  gap: {marginTop: 16},
  text: {color: '#d1d5db', lineHeight: 20, marginBottom: 10},
  small: {color: '#9ca3af', fontSize: 12, lineHeight: 17, marginTop: 8},
  mono: {color: '#d1d5db', fontFamily: 'monospace', fontSize: 12, marginTop: 2},
  row: {flexDirection: 'row', gap: 8, flexWrap: 'wrap'},
  rowBetween: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  pillRow: {flexDirection: 'row', alignItems: 'center', marginTop: 4},
  dot: {width: 10, height: 10, borderRadius: 5, marginRight: 8},
  stateText: {color: '#fff', fontSize: 15},
  input: {backgroundColor: '#0b0f14', color: '#fff', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 8, marginBottom: 10},
  btn: {borderWidth: 1, borderColor: '#374151', borderRadius: 8, paddingHorizontal: 14, paddingVertical: 10, alignSelf: 'flex-start'},
  btnPrimary: {backgroundColor: '#1db954', borderColor: '#1db954'},
  btnText: {color: '#e5e7eb', fontWeight: '600'},
  btnTextPrimary: {color: '#04210f'},
  footer: {color: '#6b7280', fontSize: 12, lineHeight: 17, marginTop: 8},
});
