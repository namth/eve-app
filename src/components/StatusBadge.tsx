import React from 'react';
import { StyleSheet, Text, View, Platform } from 'react-native';
import { EVEExpression } from '../types/api';

interface Props {
  expression: EVEExpression;
  isListening?: boolean;
}

export const StatusBadge: React.FC<Props> = ({ expression, isListening }) => {
  const getStatusText = () => {
    if (isListening) return 'LISTENING_VOICE';
    switch (expression) {
      case 'idle':
        return 'WAITING_COMMAND';
      case 'happy':
        return 'HAPPY_STATE';
      case 'smile':
        return 'SMILE_STATE';
      case 'sad':
        return 'SAD_STATE';
      case 'thinking':
        return 'THINKING_STATE';
      case 'speaking':
        return 'SPEAKING';
      case 'sleeping':
        return 'SLEEPING_STATE';
      case 'wakeup':
        return 'WAKING_UP';
      default:
        return 'WAITING_COMMAND';
    }
  };

  const getStatusColor = () => {
    if (isListening) return '#38bdf8'; // Sky blue
    switch (expression) {
      case 'idle':
        return '#10b981'; // Emerald
      case 'happy':
        return '#34d399'; // Emerald light
      case 'smile':
        return '#38bdf8'; // Sky
      case 'sad':
        return '#f43f5e'; // Rose
      case 'thinking':
        return '#fbbf24'; // Amber
      case 'speaking':
        return '#38bdf8'; // Sky
      case 'sleeping':
        return '#818cf8'; // Indigo
      case 'wakeup':
        return '#f59e0b'; // Amber dark
      default:
        return '#10b981';
    }
  };

  const statusColor = getStatusColor();

  return (
    <View style={styles.badge}>
      <View style={[styles.dot, { backgroundColor: statusColor }]} />
      <Text style={styles.prefix}>EVE: </Text>
      <Text style={[styles.statusText, { color: statusColor }]}>
        {getStatusText()}
      </Text>
    </View>
  );
};

const styles = StyleSheet.create({
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(15, 23, 42, 0.85)',
    borderWidth: 1,
    borderColor: 'rgba(30, 41, 59, 0.9)',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 20,
    alignSelf: 'center',
    marginVertical: 0,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 8,
  },
  prefix: {
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: '600',
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  statusText: {
    fontSize: 12,
    fontWeight: '700',
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
});
