package net.runelite.client.plugins.tamagotchi;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;

/**
 * Renders the 8 Tamagotchi menu icons from FontAwesome SVG path data.
 */
class TamaIcons
{
	// FontAwesome icon SVG paths and their viewBox widths (height is always 512)
	private static final int[] VIEWBOX_W = {448, 384, 512, 512, 512, 576, 640, 640};
	private static final String[] SVG_PATHS = {
		// 0: faUtensils (Meal)
		"M416 0C400 0 288 32 288 176L288 288C288 323.3 316.7 352 352 352L384 352L384 480C384 497.7 398.3 512 416 512C433.7 512 448 497.7 448 480L448 352L448 240L448 32C448 14.3 433.7 0 416 0ZM64 16C64 7.8 57.9 1 49.7 0.1C41.5 -0.8 34.2 4.6 32.4 12.5L2.1 148.8C0.7 155.1 0 161.5 0 167.9C0 213.8 35.1 251.5 80 255.6L80 480C80 497.7 94.3 512 112 512C129.7 512 144 497.7 144 480L144 255.6C188.9 251.5 224 213.8 224 167.9C224 161.5 223.3 155.1 221.9 148.8L191.6 12.5C189.8 4.5 182.3 -0.8 174.3 0.1C166.1 1 160 7.8 160 16L160 150.2C160 155.6 155.6 160 150.2 160C145.1 160 140.9 156.1 140.4 151L127.9 14.6C127.2 6.3 120.3 0 112 0C103.7 0 96.8 6.3 96.1 14.6L83.7 151C83.2 156.1 79 160 73.9 160C68.5 160 64.1 155.6 64.1 150.2L64 16Z",
		// 1: faLightbulb (Light)
		"M272 384C281.6 352.1 301.5 324.9 321.2 297.8C326.4 290.7 331.6 283.6 336.6 276.4C356.4 247.9 368 213.4 368 176C368 78.8 289.2 0 192 0C94.8 0 16 78.8 16 176C16 213.3 27.6 247.9 47.4 276.3C52.4 283.5 57.6 290.6 62.8 297.7C82.6 324.8 102.5 352.1 112 384L272 384ZM192 512C236.2 512 272 476.2 272 432L272 416L112 416L112 432C112 476.2 147.8 512 192 512ZM112 176C112 184.8 104.8 192 96 192C87.2 192 80 184.8 80 176C80 114.1 130.1 64 192 64C200.8 64 208 71.2 208 80C208 88.8 200.8 96 192 96C147.8 96 112 131.8 112 176Z",
		// 2: faBaseballBatBall (Game)
		"M424 0C411.6 0 399.8 4.9 391 13.7L233.5 171.2C223 181.7 213.7 193.3 205.8 205.8L132.7 321.6C125.4 333.1 116.9 343.8 107.2 353.5L69.9 390.7L121.2 442L158.5 404.7C168.1 395.1 178.8 386.5 190.4 379.2L306.2 306.1C318.7 298.2 330.3 288.9 340.8 278.4L498.3 121C507 112.3 512 100.4 512 88C512 75.6 507.1 63.8 498.3 55L457 13.7C448.2 4.9 436.4 0 424 0ZM512 432C512 387.8 476.2 352 432 352C387.8 352 352 387.8 352 432C352 476.2 387.8 512 432 512C476.2 512 512 476.2 512 432ZM15 399C5.6 408.4 5.6 423.6 15 433L79 497C88.4 506.4 103.6 506.4 113 497C122.4 487.6 122.4 472.4 113 463L49 399C39.6 389.6 24.4 389.6 15 399Z",
		// 3: faSyringe (Medicine)
		"M441 7L473 39L505 71C514.4 80.4 514.4 95.6 505 105C495.6 114.4 480.4 114.4 471 105L456 90L417.9 128L472.9 183C482.3 192.4 482.3 207.6 472.9 217C463.5 226.4 448.3 226.4 438.9 217L366.9 145L295 73C285.6 63.6 285.6 48.4 295 39C304.4 29.6 319.6 29.6 329 39L384 94L422.1 56L407 41C397.6 31.6 397.6 16.4 407 7C416.4 -2.4 431.6 -2.4 441 7ZM210.3 155.7L271.4 94.6C271.7 94.9 272 95.3 272.4 95.6L288.4 111.6L344.4 167.6L400.4 223.6L416.4 239.6C416.7 239.9 417 240.2 417.4 240.6L226.4 431.6C215.9 442.1 201.7 448 186.8 448L98 448L41 505C31.6 514.4 16.4 514.4 7 505C-2.4 495.6 -2.4 480.4 7 471L64 414L64 325.2C64 310.3 69.9 296.1 80.4 285.6L123.7 242.3L180.7 299.3C186.9 305.5 197.1 305.5 203.3 299.3C209.5 293.1 209.5 282.9 203.3 276.7L146.3 219.7L187.7 178.3L244.7 235.3C250.9 241.5 261.1 241.5 267.3 235.3C273.5 229.1 273.5 218.9 267.3 212.7L210.3 155.7Z",
		// 4: faShower (Clean)
		"M64 131.9C64 112.1 80.1 96 99.9 96C109.4 96 118.5 99.8 125.3 106.5L141.5 122.7C120.5 161.6 124.1 210.2 152.4 245.7L151 247C141.6 256.4 141.6 271.6 151 281C160.4 290.4 175.6 290.4 185 281L345 121C354.4 111.6 354.4 96.4 345 87C335.6 77.6 320.4 77.6 311 87L309.7 88.3C274.2 60 225.5 56.4 186.7 77.4L170.5 61.3C151.8 42.5 126.4 32 99.9 32C44.7 32 0 76.7 0 131.9L0 448C0 465.7 14.3 480 32 480C49.7 480 64 465.7 64 448L64 131.9ZM256 352C273.7 352 288 337.7 288 320C288 302.3 273.7 288 256 288C238.3 288 224 302.3 224 320C224 337.7 238.3 352 256 352ZM320 448C337.7 448 352 433.7 352 416C352 398.3 337.7 384 320 384C302.3 384 288 398.3 288 416C288 433.7 302.3 448 320 448ZM352 320C352 302.3 337.7 288 320 288C302.3 288 288 302.3 288 320C288 337.7 302.3 352 320 352C337.7 352 352 337.7 352 320ZM384 448C401.7 448 416 433.7 416 416C416 398.3 401.7 384 384 384C366.3 384 352 398.3 352 416C352 433.7 366.3 448 384 448ZM416 320C416 302.3 401.7 288 384 288C366.3 288 352 302.3 352 320C352 337.7 366.3 352 384 352C401.7 352 416 337.7 416 320ZM448 448C465.7 448 480 433.7 480 416C480 398.3 465.7 384 448 384C430.3 384 416 398.3 416 416C416 433.7 430.3 448 448 448ZM512 320C512 302.3 497.7 288 480 288C462.3 288 448 302.3 448 320C448 337.7 462.3 352 480 352C497.7 352 512 337.7 512 320Z",
		// 5: faDeezer (Stats)
		"M451.46 244.71L576 244.71L576 172L451.46 172ZM451.46 70.82L451.46 143.49L576 143.49L576 70.82ZM451.46 345.88L576 345.88L576 273.2L451.46 273.2ZM0 447.09L124.54 447.09L124.54 374.42L0 374.42ZM150.47 447.09L275 447.09L275 374.42L150.47 374.42ZM301 447.09L425.53 447.09L425.53 374.42L301 374.42ZM451.46 447.09L576 447.09L576 374.42L451.46 374.42ZM301 345.88L425.53 345.88L425.53 273.2L301 273.2ZM150.47 345.88L275 345.88L275 273.2L150.47 273.2ZM150.47 244.71L275 244.71L275 172L150.47 172Z",
		// 6: faHeadSideCough (Scold)
		"M0 224.2C0 100.6 100.2 0 224 0L248 0C343.2 0 429.2 69.3 445.3 160.2C447.6 173.2 452.1 185.9 460.4 196.2L502.4 248.8C508.6 256.6 512 266.2 512 276.2C512 300.4 492.4 320 468.2 320L448 320L448 352L339.2 365.6C328.2 367 320 376.3 320 387.4C320 399 329 408.6 340.6 409.3L448 416L448 432C448 458.5 426.5 480 400 480L320 480L320 488C320 501.3 309.3 512 296 512L256 512L96 512C78.3 512 64 497.7 64 480L64 407.3C64 390.6 57.1 374.8 46.9 361.5C16.6 322.4 0 274.1 0 224.2ZM352 224C369.7 224 384 209.7 384 192C384 174.3 369.7 160 352 160C334.3 160 320 174.3 320 192C320 209.7 334.3 224 352 224ZM464 384C464 370.7 474.7 360 488 360C501.3 360 512 370.7 512 384C512 397.3 501.3 408 488 408C474.7 408 464 397.3 464 384ZM616 360C629.3 360 640 370.7 640 384C640 397.3 629.3 408 616 408C602.7 408 592 397.3 592 384C592 370.7 602.7 360 616 360ZM592 480C592 466.7 602.7 456 616 456C629.3 456 640 466.7 640 480C640 493.3 629.3 504 616 504C602.7 504 592 493.3 592 480ZM552 312C552 298.7 562.7 288 576 288C589.3 288 600 298.7 600 312C600 325.3 589.3 336 576 336C562.7 336 552 325.3 552 312ZM616 288C629.3 288 640 298.7 640 312C640 325.3 629.3 336 616 336C602.7 336 592 325.3 592 312C592 298.7 602.7 288 616 288ZM552 408C552 394.7 562.7 384 576 384C589.3 384 600 394.7 600 408C600 421.3 589.3 432 576 432C562.7 432 552 421.3 552 408Z",
		// 7: faMasksTheater (Attention)
		"M74.6 373.2C116.3 409.3 182.6 455.7 240.7 446.9C246.8 446 252.8 444.4 258.7 442.4C249.5 430.1 241.4 418 234.5 407C212.6 372 205.7 331.8 208.6 293.4C188 297.5 169.4 306.4 153.9 318.8C147.4 324 137.6 320.1 139.1 311.8C145.5 278.3 172.1 250.9 207.3 245.5C209.9 245.1 212.6 244.8 215.2 244.7L234.6 113.4C236.6 99.6 242.6 80.7 259.6 67.5C278.2 53.2 310.5 37 363.2 32.2C362.4 31.5 361.6 30.8 360.8 30.1C340.6 14.5 288.4 -11.5 175.7 5.6C63 22.7 20.5 63 5.7 83.9C0 91.9 -0.8 102 0.6 111.8L24.8 276.1C30.3 313.4 46.3 348.7 74.6 373.2ZM162.3 153.6C166.7 150.5 173.1 151.6 174.1 156.9C174.2 157.4 174.3 158 174.4 158.5C177.6 180.3 162.8 200.5 141.3 203.8C119.8 207.1 99.8 192 96.6 170.3C96.5 169.8 96.5 169.2 96.4 168.7C95.8 163.3 101.6 160.3 106.7 162C115.7 165 125.5 165.9 135.4 164.4C145.3 162.9 154.5 159.1 162.3 153.6ZM261.6 390C291 436.9 341.1 500.9 399.2 509.7C457.3 518.5 523.7 472.2 565.3 436C593.6 411.5 609.6 376.2 615.1 338.8L639.3 174.5C640.7 164.7 639.9 154.6 634.2 146.6C619.4 125.7 576.9 85.4 464.2 68.3C351.5 51.2 299.4 77.2 279.2 92.8C271.4 98.8 267.7 108.2 266.3 118L242.1 282.3C236.6 319.6 241.7 358.1 261.6 390ZM404.5 235.3C396.8 229.8 387.7 226 377.7 224.5C367.7 223 358 223.9 349 226.9C343.9 228.6 338.1 225.6 338.7 220.2C338.8 219.7 338.8 219.1 339 218.6C342.2 196.8 362.2 181.8 383.7 185.1C405.2 188.4 419.9 208.6 416.8 230.4C416.7 230.9 416.6 231.5 416.5 232C415.5 237.3 409.1 238.4 404.5 235.3ZM540.7 250.8C539.7 256.1 533.3 257.2 528.7 254.1C521 248.6 511.9 244.8 501.9 243.3C491.9 241.8 482.2 242.7 473.2 245.7C468.1 247.4 462.3 244.4 462.9 239C463 238.5 463 237.9 463.2 237.4C466.4 215.6 486.4 200.6 507.9 203.9C529.4 207.2 544 227.4 540.9 249.2C540.8 249.7 540.7 250.3 540.6 250.8ZM530 350.2C510.4 394.9 463.2 422.7 413.2 415.1C363.2 407.5 326.1 366.9 320.2 318.4C319.2 310.1 329.1 306.3 335.4 311.7C359.3 332.5 389 347 422.4 352C455.8 357 488.5 352.1 517.3 339.2C524.9 335.8 533.3 342.4 530 350.2Z",
	};

	static BufferedImage[] createIcons(int size, Color color)
	{
		BufferedImage[] images = new BufferedImage[8];
		for (int i = 0; i < 8; i++)
		{
			images[i] = renderSvgIcon(SVG_PATHS[i], VIEWBOX_W[i], 512, size, color);
		}
		return images;
	}

	private static BufferedImage renderSvgIcon(String pathData, int vbW, int vbH, int size, Color color)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		GeneralPath path = parseSvgPath(pathData);

		// Scale to fit in size x size with padding
		float pad = size * 0.1f;
		float drawSize = size - pad * 2;
		float scaleX = drawSize / vbW;
		float scaleY = drawSize / vbH;
		float scale = Math.min(scaleX, scaleY);
		float offsetX = pad + (drawSize - vbW * scale) / 2f;
		float offsetY = pad + (drawSize - vbH * scale) / 2f;

		AffineTransform at = new AffineTransform();
		at.translate(offsetX, offsetY);
		at.scale(scale, scale);
		path.transform(at);

		g.setColor(color);
		g.fill(path);
		g.dispose();
		return img;
	}

	/**
	 * Minimal SVG path parser supporting M, L, C, Z, and their lowercase variants.
	 */
	static GeneralPath parseSvgPath(String d)
	{
		GeneralPath path = new GeneralPath();
		float cx = 0, cy = 0;
		float mx = 0, my = 0;
		int i = 0;
		char cmd = 'M';
		float[] nums = new float[64];

		while (i < d.length())
		{
			char ch = d.charAt(i);
			if (Character.isWhitespace(ch) || ch == ',')
			{
				i++;
				continue;
			}
			if (Character.isLetter(ch))
			{
				cmd = ch;
				i++;
				continue;
			}

			// Parse numbers for current command
			int numCount = 0;
			while (i < d.length() && numCount < nums.length)
			{
				while (i < d.length() && (d.charAt(i) == ',' || d.charAt(i) == ' '))
				{
					i++;
				}
				if (i >= d.length() || (Character.isLetter(d.charAt(i)) && d.charAt(i) != 'e' && d.charAt(i) != 'E'))
				{
					break;
				}
				int start = i;
				if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+'))
				{
					i++;
				}
				while (i < d.length() && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.'))
				{
					i++;
				}
				// Handle exponent
				if (i < d.length() && (d.charAt(i) == 'e' || d.charAt(i) == 'E'))
				{
					i++;
					if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+'))
					{
						i++;
					}
					while (i < d.length() && Character.isDigit(d.charAt(i)))
					{
						i++;
					}
				}
				if (start == i)
				{
					break;
				}
				nums[numCount++] = Float.parseFloat(d.substring(start, i));

				// Check if we have enough params for this command
				int needed = paramsNeeded(cmd);
				if (numCount >= needed)
				{
					executeCommand(path, cmd, nums, numCount);
					float[] result = getEndPoint(cmd, nums, cx, cy);
					cx = result[0];
					cy = result[1];
					if (cmd == 'M')
					{
						mx = cx;
						my = cy;
						cmd = 'L';
					}
					else if (cmd == 'm')
					{
						mx = cx;
						my = cy;
						cmd = 'l';
					}
					numCount = 0;
				}
			}

			if (cmd == 'Z' || cmd == 'z')
			{
				path.closePath();
				cx = mx;
				cy = my;
			}
		}
		return path;
	}

	private static int paramsNeeded(char cmd)
	{
		switch (Character.toUpperCase(cmd))
		{
			case 'M':
			case 'L':
				return 2;
			case 'C':
				return 6;
			case 'S':
				return 4;
			case 'Q':
				return 4;
			case 'H':
			case 'V':
				return 1;
			case 'Z':
				return 0;
			default:
				return 2;
		}
	}

	private static void executeCommand(GeneralPath path, char cmd, float[] n, int count)
	{
		switch (cmd)
		{
			case 'M':
				path.moveTo(n[0], n[1]);
				break;
			case 'm':
				// relative handled via getEndPoint; for simplicity treat as absolute after transform
				path.moveTo(n[0], n[1]);
				break;
			case 'L':
				path.lineTo(n[0], n[1]);
				break;
			case 'l':
				path.lineTo(n[0], n[1]);
				break;
			case 'H':
				path.lineTo(n[0], path.getCurrentPoint().getY());
				break;
			case 'V':
				path.lineTo(path.getCurrentPoint().getX(), n[0]);
				break;
			case 'C':
				path.curveTo(n[0], n[1], n[2], n[3], n[4], n[5]);
				break;
			case 'c':
				path.curveTo(n[0], n[1], n[2], n[3], n[4], n[5]);
				break;
			case 'S':
				// Smooth cubic — reflect last control point
				path.curveTo(n[0], n[1], n[0], n[1], n[2], n[3]);
				break;
			case 'Q':
				path.quadTo(n[0], n[1], n[2], n[3]);
				break;
			case 'Z':
			case 'z':
				path.closePath();
				break;
		}
	}

	private static float[] getEndPoint(char cmd, float[] n, float cx, float cy)
	{
		switch (cmd)
		{
			case 'M':
			case 'L':
				return new float[]{n[0], n[1]};
			case 'm':
			case 'l':
				return new float[]{cx + n[0], cy + n[1]};
			case 'H':
				return new float[]{n[0], cy};
			case 'h':
				return new float[]{cx + n[0], cy};
			case 'V':
				return new float[]{cx, n[0]};
			case 'v':
				return new float[]{cx, cy + n[0]};
			case 'C':
				return new float[]{n[4], n[5]};
			case 'c':
				return new float[]{cx + n[4], cy + n[5]};
			case 'S':
			case 'Q':
				return new float[]{n[2], n[3]};
			case 's':
			case 'q':
				return new float[]{cx + n[2], cy + n[3]};
			default:
				return new float[]{cx, cy};
		}
	}
}
