import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { EVEExpression } from '../types/api';

interface Props {
  currentExpression: EVEExpression;
  onSelectExpression: (exp: EVEExpression) => void;
  onOpenSettings?: () => void;
  onOpenAvatarSelector?: () => void;
  onTriggerScan?: () => void;
  avatarMode?: 'robot' | 'human';
}

export const ControlPanel: React.FC<Props> = ({
  currentExpression,
  onSelectExpression,
  onOpenSettings,
  onOpenAvatarSelector,
  avatarMode = 'robot',
}) => {
  const expressions: { key: EVEExpression; label: string; icon: string }[] = [
    { key: 'idle', label: 'Idle', icon: '🤖' },
    { key: 'happy', label: 'Happy', icon: '😄' },
    { key: 'smile', label: 'Smile', icon: '😊' },
    { key: 'sad', label: 'Sad', icon: '🥺' },
    { key: 'thinking', label: 'Think', icon: '🧠' },
    { key: 'speaking', label: 'Speak', icon: '💬' },
    { key: 'sleeping', label: 'Sleep', icon: '🌙' },
    { key: 'wakeup', label: 'Wakeup', icon: '☀️' },
  ];

  return (
    <View style={styles.floatingPanel}>
      {onOpenAvatarSelector && (
        <TouchableOpacity
          activeOpacity={0.7}
          style={[styles.btn, { borderColor: '#00f0ff', backgroundColor: 'rgba(0, 240, 255, 0.25)' }]}
          onPress={onOpenAvatarSelector}
        >
          <Text style={styles.btnIcon}>{avatarMode === 'human' ? '👩' : '🤖'}</Text>
        </TouchableOpacity>
      )}

      {onOpenSettings && (
        <TouchableOpacity
          activeOpacity={0.7}
          style={[styles.btn, { borderColor: 'rgba(255, 255, 255, 0.3)', backgroundColor: 'rgba(15, 23, 42, 0.85)' }]}
          onPress={onOpenSettings}
        >
          <Text style={styles.btnIcon}>⚙️</Text>
        </TouchableOpacity>
      )}

      {expressions.map((item) => {
        const isActive = currentExpression === item.key;
        return (
          <TouchableOpacity
            key={item.key}
            activeOpacity={0.7}
            style={[styles.btn, isActive && styles.btnActive]}
            onPress={() => onSelectExpression(item.key)}
          >
            <Text style={styles.btnIcon}>{item.icon}</Text>
            {isActive && <Text style={styles.btnLabelActive}>{item.label}</Text>}
          </TouchableOpacity>
        );
      })}
    </View>
  );
};

const styles = StyleSheet.create({
  floatingPanel: {
    position: 'absolute',
    right: 12,
    top: 130,
    zIndex: 50,
    gap: 10,
    alignItems: 'flex-end',
  },
  btn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(15, 23, 42, 0.85)',
    width: 42,
    height: 42,
    borderRadius: 21,
    borderWidth: 1,
    borderColor: 'rgba(51, 65, 85, 0.8)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 3 },
    shadowOpacity: 0.4,
    shadowRadius: 5,
    elevation: 5,
  },
  btnActive: {
    width: 'auto',
    paddingHorizontal: 12,
    backgroundColor: 'rgba(14, 165, 233, 0.4)',
    borderColor: '#0ea5e9',
    shadowColor: '#00f0ff',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.8,
    shadowRadius: 8,
  },
  btnIcon: {
    fontSize: 16,
  },
  btnLabelActive: {
    color: '#38bdf8',
    fontSize: 12,
    fontWeight: '700',
    marginLeft: 6,
  },
});
