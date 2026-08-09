import { useState, useEffect } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { AvatarMode, HumanAvatarConfig } from '../types/avatar';

const STORAGE_AVATAR_MODE = '@eve_avatar_mode';
const STORAGE_HUMAN_CONFIG = '@eve_human_avatar_config';

const DEFAULT_HUMAN_CONFIG: HumanAvatarConfig = {
  type: 'preset',
  presetId: 'office_girl',
  customUri: null,
};

export function useAvatarMode() {
  const [avatarMode, setAvatarMode] = useState<AvatarMode>('robot');
  const [humanConfig, setHumanConfig] = useState<HumanAvatarConfig>(DEFAULT_HUMAN_CONFIG);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  // Load avatar settings on initial mount
  useEffect(() => {
    async function loadSettings() {
      try {
        const savedMode = await AsyncStorage.getItem(STORAGE_AVATAR_MODE);
        if (savedMode === 'human' || savedMode === 'robot') {
          setAvatarMode(savedMode);
        }

        const savedConfigStr = await AsyncStorage.getItem(STORAGE_HUMAN_CONFIG);
        if (savedConfigStr) {
          const parsed = JSON.parse(savedConfigStr);
          setHumanConfig(parsed);
        }
      } catch (error) {
        console.error('Error loading avatar settings:', error);
      } finally {
        setIsLoading(false);
      }
    }
    loadSettings();
  }, []);

  // Update Avatar Mode (Robot vs Human)
  const setMode = async (mode: AvatarMode) => {
    setAvatarMode(mode);
    try {
      await AsyncStorage.setItem(STORAGE_AVATAR_MODE, mode);
    } catch (error) {
      console.error('Error saving avatar mode:', error);
    }
  };

  // Update Human Avatar Configuration (Preset / Custom photo)
  const updateHumanConfig = async (newConfig: HumanAvatarConfig) => {
    setHumanConfig(newConfig);
    try {
      await AsyncStorage.setItem(STORAGE_HUMAN_CONFIG, JSON.stringify(newConfig));
    } catch (error) {
      console.error('Error saving human avatar config:', error);
    }
  };

  return {
    avatarMode,
    setMode,
    humanConfig,
    updateHumanConfig,
    isLoading,
  };
}
