package kit.penny.clientbus.connector.dikidi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DikidiOwnerAvatarTest {
    @Test void readsOnlyTheAuthenticatedUserMenuAndDecodesAttributes() {
        assertEquals("https://f2.dikidi.net/c01/v00001/owner.jpg?a=1&b=2", DikidiOwnerAvatar.parse("""
            <html><body><img src="project.jpg"><ul>
            <li class="dropdown user"><a><div class="ico-box"><div class="icon img-circle">
              <img src="https://f2.dikidi.net/c01/v00001/owner.jpg?a=1&amp;b=2">
            </div></div><div class="data-box">Name</div></a>
            <ul><li><img src="unrelated-menu-item.jpg"></li></ul></li></ul>
            <div class="icon img-circle"><img src="participant.jpg"></div></body></html>
            """));
    }
    @Test void missingOrAmbiguousMenuDoesNotGuessFromOtherPhotos() {
        assertNull(DikidiOwnerAvatar.parse("<img src='client.jpg'>"));
        assertNull(DikidiOwnerAvatar.parse("<li class='user-other dropdown'><div class='icon img-circle'><img src='wrong.jpg'></div></li>"));
        assertNull(DikidiOwnerAvatar.parse("<li class='dropdown user'><div class='icon img-circle'><img src='a.jpg'><img src='b.jpg'></div></li>"));
        assertNull(DikidiOwnerAvatar.parse(null));
    }
}
