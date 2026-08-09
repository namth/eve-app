import { pronunciationDictionaryService } from './pronunciationDictionaryService';

/**
 * Service Chuẩn hóa Văn bản cho Giọng đọc TTS (Text-to-Speech Normalizer)
 * Tự động chuyển đổi các địa chỉ Website / Domain Name (ví dụ: hoangskitchenhoian.com)
 * thành dạng phiên âm Tiếng Việt tự nhiên ("hoàng s kitchen hội an chấm com")
 * thay vì đọc đánh vần từng ký tự (h-o-a-n-g-s-...).
 */

// 1. Danh sách các Domain nổi tiếng được chuẩn hóa trực tiếp
const EXACT_DOMAIN_MAP: Record<string, string> = {
  'hoangskitchenhoian.com': 'hoàng s kitchen hội an chấm com',
  'vnexpress.net': 'vn express chấm nét',
  'tuoitre.vn': 'tuổi trẻ chấm vi en',
  'thanhnien.vn': 'thanh niên chấm vi en',
  'dantri.com.vn': 'dân trí chấm com chấm vi en',
  'chotot.com': 'chợ tốt chấm com',
  'vietnamnet.vn': 'việt nam nét chấm vi en',
  'facebook.com': 'phây súp chấm com',
  'youtube.com': 'iu tuýp chấm com',
  'google.com': 'gút gồ chấm com',
  'google.com.vn': 'gút gồ chấm com chấm vi en',
  'zalo.me': 'za lô chấm mi',
  'tiktok.com': 'tích tắc chấm com',
  'shopee.vn': 'shopee chấm vi en',
  'lazada.vn': 'lazada chấm vi en',
  'tiki.vn': 'tiki chấm vi en',
};

// 2. Từ điển nhận diện từ ghép phổ biến trong tên miền (Tiếng Việt & Tiếng Anh)
const DOMAIN_WORD_SEGMENTS: Array<{ pattern: RegExp; replacement: string }> = [
  // Tên địa danh / từ Tiếng Việt thông dụng
  { pattern: /hoian/gi, replacement: ' hội an ' },
  { pattern: /danang/gi, replacement: ' đà nẵng ' },
  { pattern: /hanoi/gi, replacement: ' hà nội ' },
  { pattern: /saigon/gi, replacement: ' sài gòn ' },
  { pattern: /nhatrang/gi, replacement: ' nha trang ' },
  { pattern: /dalat/gi, replacement: ' đà lạt ' },
  { pattern: /phuquoc/gi, replacement: ' phú quốc ' },
  { pattern: /vietnam/gi, replacement: ' việt nam ' },
  { pattern: /amthuc/gi, replacement: ' ẩm thực ' },
  { pattern: /nhahang/gi, replacement: ' nhà hàng ' },
  { pattern: /khachsan/gi, replacement: ' khách sạn ' },
  { pattern: /quan/gi, replacement: ' quán ' },
  { pattern: /bep/gi, replacement: ' bếp ' },
  { pattern: /tiem/gi, replacement: ' tiệm ' },

  // Tên từ Tiếng Anh thông dụng trong tên miền
  { pattern: /kitchen/gi, replacement: ' kitchen ' },
  { pattern: /restaurant/gi, replacement: ' restaurant ' },
  { pattern: /resort/gi, replacement: ' resort ' },
  { pattern: /hotel/gi, replacement: ' hotel ' },
  { pattern: /homestay/gi, replacement: ' homestay ' },
  { pattern: /villa/gi, replacement: ' villa ' },
  { pattern: /house/gi, replacement: ' house ' },
  { pattern: /home/gi, replacement: ' home ' },
  { pattern: /coffee/gi, replacement: ' coffee ' },
  { pattern: /cafe/gi, replacement: ' cafe ' },
  { pattern: /store/gi, replacement: ' store ' },
  { pattern: /shop/gi, replacement: ' shop ' },
  { pattern: /travel/gi, replacement: ' travel ' },
  { pattern: /tour/gi, replacement: ' tour ' },
  { pattern: /food/gi, replacement: ' food ' },
  { pattern: /drink/gi, replacement: ' drink ' },
  { pattern: /beauty/gi, replacement: ' beauty ' },
  { pattern: /spa/gi, replacement: ' spa ' },
  { pattern: /studio/gi, replacement: ' studio ' },
  { pattern: /media/gi, replacement: ' media ' },
  { pattern: /official/gi, replacement: ' official ' },
  { pattern: /service/gi, replacement: ' service ' },
  { pattern: /group/gi, replacement: ' group ' },
  { pattern: /express/gi, replacement: ' express ' },
  { pattern: /online/gi, replacement: ' online ' },
  { pattern: /digital/gi, replacement: ' digital ' },
  { pattern: /mobile/gi, replacement: ' mobile ' },
  { pattern: /booking/gi, replacement: ' booking ' },
  { pattern: /ticket/gi, replacement: ' ticket ' },
  { pattern: /flight/gi, replacement: ' flight ' },
  { pattern: /bank/gi, replacement: ' bank ' },
  { pattern: /mart/gi, replacement: ' mart ' },
  { pattern: /market/gi, replacement: ' market ' },

  // Sở hữu cách 's (ví dụ hoangs -> hoàng s)
  { pattern: /([a-z]+)s\b/gi, replacement: '$1 s' },
];

/**
 * Chuẩn hóa một URL hoặc Tên miền thành văn bản đọc tự nhiên
 */
export function normalizeUrlForSpeech(url: string): string {
  if (!url) return '';
  const originalUrl = url.trim();
  const cleanUrl = originalUrl.toLowerCase();

  // 1. Kiểm tra bảng tra trực tiếp
  if (EXACT_DOMAIN_MAP[cleanUrl]) {
    return EXACT_DOMAIN_MAP[cleanUrl];
  }

  // 2. Xử lý ranh giới CamelCase trước khi chuyển sang viết thường hoàn toàn (Ví dụ: HoangsKitchenHoiAn.com)
  let processed = originalUrl
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/([a-zA-Z])(\d)/g, '$1 $2')
    .replace(/(\d)([a-zA-Z])/g, '$1 $2');

  // 3. Tách bỏ protocol (https://, http://, www.)
  processed = processed
    .replace(/^https?:\/\//i, '')
    .replace(/^www\./i, '');

  // 4. Thay thế các đuôi Tên miền (TLD Extensions)
  processed = processed
    .replace(/\.com\.vn/gi, ' chấm com chấm vi en')
    .replace(/\.edu\.vn/gi, ' chấm e đu chấm vi en')
    .replace(/\.gov\.vn/gi, ' chấm gốp chấm vi en')
    .replace(/\.com/gi, ' chấm com')
    .replace(/\.vn/gi, ' chấm vi en')
    .replace(/\.net/gi, ' chấm nét')
    .replace(/\.org/gi, ' chấm org')
    .replace(/\.info/gi, ' chấm in pho')
    .replace(/\.biz/gi, ' chấm bít')
    .replace(/\.io/gi, ' chấm ai ô')
    .replace(/\.ai/gi, ' chấm ei ai')
    .replace(/\.me/gi, ' chấm mi')
    .replace(/\.site/gi, ' chấm site')
    .replace(/\//g, ' xuyệt ')
    .replace(/[-_]/g, ' ');

  // 5. Phân đoạn từ ghép cho slug tên miền (ví dụ: hoangskitchenhoian -> hoangs kitchen hoi an)
  for (const item of DOMAIN_WORD_SEGMENTS) {
    processed = processed.replace(item.pattern, item.replacement);
  }

  // 6. Làm sạch khoảng trắng thừa
  return processed.replace(/\s+/g, ' ').trim();
}

export const ttsNormalizerService = {
  /**
   * Tự động quét toàn bộ văn bản câu thoại, phát hiện các URL / Tên miền
   * và chuyển đổi chúng thành dạng âm đọc Tiếng Việt tự nhiên trước khi phát TTS.
   * Ưu tiên hàng đầu (Highest Priority): Kiểm tra từ điển học được (_eve_pronunciation_db.json).
   */
  async normalizeForTTS(text: string): Promise<string> {
    if (!text) return '';

    // 1. Ưu tiên hàng đầu: Áp dụng từ điển phát âm học được
    let processedText = await pronunciationDictionaryService.applyLearnedDictionary(text);

    // 2. Tiếp theo: Quét tự động các URL / Tên miền chưa nằm trong từ điển học được
    const urlRegex = /(?:https?:\/\/)?(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(?:\/[^\s]*)?/gi;

    return processedText.replace(urlRegex, (matchedUrl) => {
      if (/^[a-zA-Z0-9-]+\.(com|vn|net|org|edu|gov|io|ai|me|info|biz)/i.test(matchedUrl) || matchedUrl.startsWith('http')) {
        return normalizeUrlForSpeech(matchedUrl);
      }
      return matchedUrl;
    });
  },
};
