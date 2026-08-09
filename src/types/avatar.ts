export type AvatarMode = 'robot' | 'human';

export type AvatarPresetId = 'office_girl' | 'dynamic_girl';

export interface HumanAvatarConfig {
  type: 'preset' | 'custom';
  presetId?: AvatarPresetId;
  customUri?: string | null;
}

export const PRESET_AVATARS: Record<AvatarPresetId, { id: AvatarPresetId; name: string; description: string; imagePath: any }> = {
  office_girl: {
    id: 'office_girl',
    name: 'Trợ lý Công sở',
    description: 'Phong cách chuyên nghiệp, thanh lịch & ấm áp',
    imagePath: require('../../assets/avatars/ai_girl_office.jpg'),
  },
  dynamic_girl: {
    id: 'dynamic_girl',
    name: 'Nữ tính Năng động',
    description: 'Phong cách hiện đại, tươi vui & sinh động',
    imagePath: require('../../assets/avatars/ai_girl_dynamic.jpg'),
  },
};
